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
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.api.exception.RecoverableType
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.FileNotFoundException
import java.util.concurrent.TimeoutException
import java.util.stream.Stream

class WowExceptionTest {
    @ParameterizedTest
    @MethodSource("argsProvider")
    fun toErrorInfo(throwable: Throwable, errorCode: String) {
        val actual = throwable.toErrorInfo()
        actual.errorCode.assert().isEqualTo(errorCode)
        actual.errorMsg.assert().isEqualTo(throwable.message)
    }

    @Test
    fun recoverable() {
        null.recoverable(TimeoutException::class.java).assert().isEqualTo(RecoverableType.RECOVERABLE)
        Retry().recoverable(TimeoutException::class.java).assert().isEqualTo(RecoverableType.RECOVERABLE)
        Retry(recoverable = arrayOf(IllegalStateException::class)).recoverable(IllegalStateException::class.java)
            .assert().isEqualTo(RecoverableType.RECOVERABLE)
        Retry(
            unrecoverable = arrayOf(IllegalArgumentException::class)
        ).recoverable(IllegalArgumentException::class.java)
            .assert().isEqualTo(RecoverableType.UNRECOVERABLE)
    }

    companion object {
        @JvmStatic
        fun argsProvider(): Stream<Arguments> {
            return Stream.of(
                Arguments.arguments(WowException("test", "test"), "test"),
                Arguments.arguments(IllegalArgumentException("test"), ErrorCodes.ILLEGAL_ARGUMENT),
                Arguments.arguments(IllegalStateException("test"), ErrorCodes.ILLEGAL_STATE),
                Arguments.arguments(TimeoutException("test"), ErrorCodes.REQUEST_TIMEOUT),
                Arguments.arguments(Exception("test"), ErrorCodes.BAD_REQUEST),
                Arguments.arguments(FileNotFoundException("test"), ErrorCodes.NOT_FOUND),
            )
        }
    }
}
