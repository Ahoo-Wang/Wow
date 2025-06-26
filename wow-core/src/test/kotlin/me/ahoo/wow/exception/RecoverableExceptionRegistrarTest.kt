package me.ahoo.wow.exception

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.exception.RecoverableExceptionRegistrar.register
import me.ahoo.wow.exception.RecoverableExceptionRegistrar.unregister
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeoutException

class RecoverableExceptionRegistrarTest {

    @Test
    fun recoverable() {
        register(IllegalArgumentException::class.java, RecoverableType.UNRECOVERABLE)
        IllegalArgumentException::class.java.recoverable.assert().isEqualTo(RecoverableType.UNRECOVERABLE)
        unregister(IllegalArgumentException::class.java)

        IllegalArgumentException::class.java.recoverable.assert().isEqualTo(RecoverableType.UNKNOWN)

        TimeoutException::class.java.recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)

        MockRecoverableException().recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)
    }

    class MockRecoverableException : RecoverableException, RuntimeException()
}
