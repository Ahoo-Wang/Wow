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
import me.ahoo.wow.exception.RecoverableExceptionRegistrar.getRecoverableType
import me.ahoo.wow.exception.RecoverableExceptionRegistrar.register
import me.ahoo.wow.exception.RecoverableExceptionRegistrar.unregister
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeoutException

class RecoverableExceptionRegistrarTest {

    @Test
    fun `getRecoverableType returns null when not registered`() {
        getRecoverableType(IllegalStateException::class.java).assert().isNull()
    }

    @Test
    fun `register and getRecoverableType`() {
        register(IllegalArgumentException::class.java, RecoverableType.UNRECOVERABLE)
        getRecoverableType(IllegalArgumentException::class.java).assert().isEqualTo(RecoverableType.UNRECOVERABLE)
        unregister(IllegalArgumentException::class.java)
    }

    @Test
    fun `register overwrites previous mapping`() {
        register(IllegalArgumentException::class.java, RecoverableType.UNRECOVERABLE)
        getRecoverableType(IllegalArgumentException::class.java).assert().isEqualTo(RecoverableType.UNRECOVERABLE)

        register(IllegalArgumentException::class.java, RecoverableType.RECOVERABLE)
        getRecoverableType(IllegalArgumentException::class.java).assert().isEqualTo(RecoverableType.RECOVERABLE)

        unregister(IllegalArgumentException::class.java)
    }

    @Test
    fun `unregister removes mapping`() {
        register(IllegalArgumentException::class.java, RecoverableType.UNRECOVERABLE)
        getRecoverableType(IllegalArgumentException::class.java).assert().isNotNull()

        unregister(IllegalArgumentException::class.java)
        getRecoverableType(IllegalArgumentException::class.java).assert().isNull()
    }

    @Test
    fun `unregister is no-op for unregistered class`() {
        unregister(IllegalStateException::class.java)
        getRecoverableType(IllegalStateException::class.java).assert().isNull()
    }

    @Test
    fun `default recoverable for RecoverableException is RECOVERABLE`() {
        MockRecoverableException().recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)
    }

    @Test
    fun `default recoverable for TimeoutException is RECOVERABLE`() {
        TimeoutException::class.java.recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)
    }

    @Test
    fun `default recoverable for unknown exception is UNKNOWN`() {
        RuntimeException::class.java.recoverable.assert().isEqualTo(RecoverableType.UNKNOWN)
    }

    @Test
    fun `Throwable recoverable delegates to Class recoverable`() {
        register(IllegalArgumentException::class.java, RecoverableType.UNRECOVERABLE)
        IllegalArgumentException().recoverable.assert().isEqualTo(RecoverableType.UNRECOVERABLE)
        unregister(IllegalArgumentException::class.java)

        IllegalArgumentException().recoverable.assert().isEqualTo(RecoverableType.UNKNOWN)
    }

    @Test
    fun `explicit registration takes precedence over default rules`() {
        register(TimeoutException::class.java, RecoverableType.UNRECOVERABLE)
        TimeoutException::class.java.recoverable.assert().isEqualTo(RecoverableType.UNRECOVERABLE)

        unregister(TimeoutException::class.java)
        TimeoutException::class.java.recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)
    }

    @Test
    fun `SPI provider is loaded via ServiceLoader`() {
        getRecoverableType(TestSpiException::class.java).assert().isEqualTo(RecoverableType.RECOVERABLE)
    }

    @Test
    fun `SPI registered exception is accessible via Class recoverable extension`() {
        TestSpiException::class.java.recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)
    }

    @Test
    fun `SPI registered exception is accessible via Throwable recoverable extension`() {
        TestSpiException().recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)
    }

    @Test
    fun `getRecoverableType resolves superclass registration for subclass`() {
        register(RuntimeException::class.java, RecoverableType.UNRECOVERABLE)
        getRecoverableType(IllegalStateException::class.java).assert()
            .isEqualTo(RecoverableType.UNRECOVERABLE)
        unregister(RuntimeException::class.java)
    }

    @Test
    fun `getRecoverableType prefers exact match over superclass`() {
        register(RuntimeException::class.java, RecoverableType.UNRECOVERABLE)
        register(IllegalStateException::class.java, RecoverableType.RECOVERABLE)
        getRecoverableType(IllegalStateException::class.java).assert()
            .isEqualTo(RecoverableType.RECOVERABLE)
        unregister(RuntimeException::class.java)
        unregister(IllegalStateException::class.java)
    }

    @Test
    fun `getRecoverableType returns null when no superclass registered`() {
        getRecoverableType(IllegalStateException::class.java).assert().isNull()
    }

    @Test
    fun `Class recoverable extension resolves superclass registration`() {
        register(RuntimeException::class.java, RecoverableType.UNRECOVERABLE)
        IllegalStateException::class.java.recoverable.assert()
            .isEqualTo(RecoverableType.UNRECOVERABLE)
        unregister(RuntimeException::class.java)
    }

    class MockRecoverableException : RecoverableException, RuntimeException()
}
