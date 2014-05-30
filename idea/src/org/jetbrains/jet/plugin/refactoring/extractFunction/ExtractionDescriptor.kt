/*
 * Copyright 2010-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.plugin.refactoring.extractFunction

import org.jetbrains.jet.lang.types.JetType
import org.jetbrains.jet.lang.psi.JetPsiFactory
import org.jetbrains.jet.lang.psi.JetCallExpression
import org.jetbrains.jet.lang.psi.JetElement
import org.jetbrains.jet.lang.types.lang.KotlinBuiltIns
import com.intellij.util.containers.MultiMap
import com.intellij.psi.PsiElement
import org.jetbrains.jet.lang.psi.JetThisExpression
import org.jetbrains.jet.plugin.references.JetSimpleNameReference
import org.jetbrains.jet.lang.resolve.name.FqName
import org.jetbrains.jet.plugin.references.JetSimpleNameReference.ShorteningMode
import org.jetbrains.jet.lang.psi.psiUtil.replaced
import org.jetbrains.jet.lang.psi.JetQualifiedExpression
import org.jetbrains.jet.lang.psi.JetTypeParameter
import org.jetbrains.jet.lang.psi.JetTypeConstraint
import kotlin.properties.Delegates
import org.jetbrains.jet.plugin.refactoring.extractFunction.AnalysisResult.Status
import org.jetbrains.jet.plugin.refactoring.JetRefactoringBundle
import org.jetbrains.jet.plugin.refactoring.extractFunction.AnalysisResult.ErrorMessage

data class Parameter(
        val argumentText: String,
        val name: String,
        var mirrorVarName: String?,
        val parameterType: JetType,
        val receiverCandidate: Boolean
) {
    val nameForRef: String get() = mirrorVarName ?: name
}

data class TypeParameter(
        val originalDeclaration: JetTypeParameter,
        val originalConstraints: List<JetTypeConstraint>
)

trait Replacement: Function1<JetElement, JetElement>

trait ParameterReplacement : Replacement {
    val parameter: Parameter
    fun copy(parameter: Parameter): ParameterReplacement
}

class RenameReplacement(override val parameter: Parameter): ParameterReplacement {
    override fun copy(parameter: Parameter) = RenameReplacement(parameter)

    [suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")]
    override fun invoke(e: JetElement): JetElement {
        val thisExpr = e.getParent() as? JetThisExpression
        return (thisExpr ?: e).replaced(JetPsiFactory.createSimpleName(e.getProject(), parameter.nameForRef))
    }
}

class AddPrefixReplacement(override val parameter: Parameter): ParameterReplacement {
    override fun copy(parameter: Parameter) = AddPrefixReplacement(parameter)

    [suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")]
    override fun invoke(e: JetElement): JetElement {
        val selector = (e.getParent() as? JetCallExpression) ?: e
        val newExpr = selector.replace(
                JetPsiFactory.createExpression(e.getProject(), "${parameter.nameForRef}.${selector.getText()}")
        ) as JetQualifiedExpression

        return with(newExpr.getSelectorExpression()!!) { if (this is JetCallExpression) getCalleeExpression()!! else this }
    }
}

class FqNameReplacement(val fqName: FqName): Replacement {
    [suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")]
    override fun invoke(e: JetElement): JetElement {
        val newExpr = (e.getReference() as? JetSimpleNameReference)?.bindToFqName(fqName, ShorteningMode.NO_SHORTENING) as JetElement
        return if (newExpr is JetQualifiedExpression) newExpr.getSelectorExpression()!! else newExpr
    }
}

trait ControlFlow {
    val returnType: JetType
}

class DefaultControlFlow(override val returnType: JetType = DEFAULT_RETURN_TYPE): ControlFlow

trait JumpBasedControlFlow : ControlFlow {
    val elementsToReplace: List<JetElement>
    val elementToInsertAfterCall: JetElement
}

class ConditionalJump(
        override val elementsToReplace: List<JetElement>,
        override val elementToInsertAfterCall: JetElement
): JumpBasedControlFlow {
    override val returnType: JetType get() = KotlinBuiltIns.getInstance().getBooleanType()
}

class UnconditionalJump(
        override val elementsToReplace: List<JetElement>,
        override val elementToInsertAfterCall: JetElement
): JumpBasedControlFlow {
    override val returnType: JetType get() = KotlinBuiltIns.getInstance().getUnitType()
}

class ExpressionEvaluation(override val returnType: JetType): ControlFlow

class ExpressionEvaluationWithCallSiteReturn(override val returnType: JetType): ControlFlow

class ParameterUpdate(val parameter: Parameter): ControlFlow {
    override val returnType: JetType get() = parameter.parameterType
}

data class ExtractionDescriptor(
        val extractionData: ExtractionData,
        val name: String,
        val visibility: String,
        val parameters: List<Parameter>,
        val receiverParameter: Parameter?,
        val typeParameters: List<TypeParameter>,
        val replacementMap: Map<Int, Replacement>,
        val controlFlow: ControlFlow
)

class AnalysisResult (
        val descriptor: ExtractionDescriptor?,
        val status: Status,
        val messages: List<ErrorMessage>
) {
    enum class Status {
        SUCCESS
        NON_CRITICAL_ERROR
        CRITICAL_ERROR
    }

    enum class ErrorMessage {
        NO_EXPRESSION
        NO_CONTAINER
        SUPER_CALL
        DENOTABLE_TYPES
        MULTIPLE_OUTPUT
        OUTPUT_AND_EXIT_POINT
        MULTIPLE_EXIT_POINTS
        VARIABLES_ARE_USED_OUTSIDE
        DECLARATIONS_OUT_OF_SCOPE

        var additionalInfo: List<String>? = null

        fun addAdditionalInfo(info: List<String>): ErrorMessage {
            additionalInfo = info
            return this
        }

        fun renderMessage(): String {
            val message = JetRefactoringBundle.message(when(this) {
                NO_EXPRESSION -> "cannot.refactor.no.expresson"
                NO_CONTAINER -> "cannot.refactor.no.container"
                SUPER_CALL -> "cannot.extract.super.call"
                DENOTABLE_TYPES -> "parameter.types.are.not.denotable"
                MULTIPLE_OUTPUT -> "selected.code.fragment.has.multiple.output.values"
                OUTPUT_AND_EXIT_POINT -> "selected.code.fragment.has.output.values.and.exit.points"
                MULTIPLE_EXIT_POINTS -> "selected.code.fragment.has.multiple.exit.points"
                VARIABLES_ARE_USED_OUTSIDE -> "variables.are.used.outside.of.selected.code.fragment"
                DECLARATIONS_OUT_OF_SCOPE -> "declarations.will.move.out.of.scope"
            })!!
            if (additionalInfo != null) {
                return "$message\n${additionalInfo?.makeString("\n")}"
            }
            return message
        }
    }
}

class ExtractionDescriptorWithConflicts(
        val descriptor: ExtractionDescriptor,
        val conflicts: MultiMap<PsiElement, String>
)
