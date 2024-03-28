package me.ahoo.wow.infra.accessor.function

import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.sameInstance
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class FunctionAccessorKtTest {

    @Test
    fun getDeclaringClass() {
        assertThat(
            FunctionAccessorKtTest::getDeclaringClass.declaringClass.java,
            sameInstance(FunctionAccessorKtTest::class.java)
        )

        assertThat(
            FunctionAccessorKtTest::extensionFunction.declaringClass.java,
            sameInstance(FunctionAccessorKtTest::class.java)
        )

        assertThat(
            ::independentFunction.declaringClass.java.name,
            equalTo("me.ahoo.wow.infra.accessor.function.FunctionAccessorKtTestKt")
        )
    }
}

fun FunctionAccessorKtTest.extensionFunction() = Unit

fun independentFunction() = Unit
