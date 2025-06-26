package me.ahoo.wow.infra.accessor.function

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class FunctionAccessorKtTest {

    @Test
    fun getDeclaringClass() {
        FunctionAccessorKtTest::getDeclaringClass.declaringClass.java.assert().isSameAs(
            FunctionAccessorKtTest::class.java
        )
        FunctionAccessorKtTest::extensionFunction.declaringClass.java.assert().isSameAs(
            FunctionAccessorKtTest::class.java
        )
        ::independentFunction.declaringClass.java.name.assert().isEqualTo(
            "me.ahoo.wow.infra.accessor.function.FunctionAccessorKtTestKt"
        )
    }
}

fun FunctionAccessorKtTest.extensionFunction() = Unit

fun independentFunction() = Unit
