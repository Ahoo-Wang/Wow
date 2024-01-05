package me.ahoo.wow.exception

import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.exception.RecoverableExceptionRegistrar.register
import me.ahoo.wow.exception.RecoverableExceptionRegistrar.unregister
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeoutException

class RecoverableExceptionRegistrarTest {

    @Test
    fun recoverable() {
        register(IllegalArgumentException::class.java, RecoverableType.UNRECOVERABLE)
        IllegalArgumentException::class.java.recoverable.let {
            assertThat(it, equalTo(RecoverableType.UNRECOVERABLE))
        }
        unregister(IllegalArgumentException::class.java)
        IllegalArgumentException::class.java.recoverable.let {
            assertThat(it, equalTo(RecoverableType.UNKNOWN))
        }

        TimeoutException::class.java.recoverable.let {
            assertThat(it, equalTo(RecoverableType.RECOVERABLE))
        }

        assertThat(MockRecoverableException().recoverable, equalTo(RecoverableType.RECOVERABLE))
    }

    class MockRecoverableException : RecoverableException, RuntimeException()
}
