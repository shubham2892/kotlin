// !DIAGNOSTICS: -UNUSED_VARIABLE -UNUSED_ANONYMOUS_PARAMETER

// FILE: A.java
import java.util.Comparator;

public class Abcdef<E> {
    public A(Comparator<? super E> comparator) {}
}

// FILE: main.kt

fun foo() {
    val result: Abcdef<String> = Abcdef<String> { x, y -> 1 }
}
