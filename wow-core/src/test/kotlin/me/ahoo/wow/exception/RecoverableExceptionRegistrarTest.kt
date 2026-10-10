/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.exception

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.RecoverableType
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeoutException

class RecoverableExceptionRegistrarTest {

    @Test
    fun `should register replace and resolve recoverable type through superclass lookup`() {
        RecoverableExceptionRegistry.DEFAULT.unregister(RegisteredRecoverableException::class.java)
        try {
            RecoverableExceptionRegistry.DEFAULT.register(
                RegisteredRecoverableException::class.java,
                RecoverableType.UNRECOVERABLE,
            )
            RecoverableExceptionRegistry.DEFAULT.getRecoverableType(RegisteredRecoverableSubclassException::class.java)
                .assert().isEqualTo(RecoverableType.UNRECOVERABLE)

            RecoverableExceptionRegistry.DEFAULT.register(
                RegisteredRecoverableException::class.java,
                RecoverableType.RECOVERABLE,
            )
            RegisteredRecoverableSubclassException::class.java.recoverable.assert()
                .isEqualTo(RecoverableType.RECOVERABLE)
        } finally {
            RecoverableExceptionRegistry.DEFAULT.unregister(RegisteredRecoverableException::class.java)
        }

        RegisteredRecoverableSubclassException::class.java.recoverable.assert().isEqualTo(RecoverableType.UNKNOWN)
    }

    @Test
    fun `should expose default recoverable classifications`() {
        MarkerRecoverableException().recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)
        TimeoutException::class.java.recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)
        RuntimeException::class.java.recoverable.assert().isEqualTo(RecoverableType.UNKNOWN)
    }

    @Test
    fun `should prefer explicit registration over default recoverable classifications`() {
        RecoverableExceptionRegistry.DEFAULT.unregister(TimeoutException::class.java)
        try {
            RecoverableExceptionRegistry.DEFAULT.register(TimeoutException::class.java, RecoverableType.UNRECOVERABLE)

            TimeoutException::class.java.recoverable.assert().isEqualTo(RecoverableType.UNRECOVERABLE)
        } finally {
            RecoverableExceptionRegistry.DEFAULT.unregister(TimeoutException::class.java)
        }

        TimeoutException::class.java.recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)
    }

    @Test
    fun `should prefer exact registration over registered superclass`() {
        RecoverableExceptionRegistry.DEFAULT.unregister(RuntimeException::class.java)
        RecoverableExceptionRegistry.DEFAULT.unregister(IllegalStateException::class.java)
        try {
            RecoverableExceptionRegistry.DEFAULT.register(RuntimeException::class.java, RecoverableType.UNRECOVERABLE)
            RecoverableExceptionRegistry.DEFAULT.register(
                IllegalStateException::class.java,
                RecoverableType.RECOVERABLE
            )

            RecoverableExceptionRegistry.DEFAULT.getRecoverableType(IllegalStateException::class.java)
                .assert().isEqualTo(RecoverableType.RECOVERABLE)
            IllegalStateException::class.java.recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)
        } finally {
            RecoverableExceptionRegistry.DEFAULT.unregister(IllegalStateException::class.java)
            RecoverableExceptionRegistry.DEFAULT.unregister(RuntimeException::class.java)
        }
    }

    @Test
    fun `should keep service loaded provider registration available`() {
        TestSpiException::class.java.recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)
        TestSpiException().recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)
    }
}

private open class RegisteredRecoverableException : RuntimeException()

private class RegisteredRecoverableSubclassException : RegisteredRecoverableException()

private class MarkerRecoverableException : RuntimeException(), RecoverableException
