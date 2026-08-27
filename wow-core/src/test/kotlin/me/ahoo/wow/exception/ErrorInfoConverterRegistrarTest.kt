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
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.exception.ErrorInfoCapable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.FileNotFoundException
import java.util.concurrent.TimeoutException
import java.util.stream.Stream

class ErrorInfoConverterRegistrarTest {

    @Test
    fun `should use service loaded converter and resolve subclass lookup`() {
        CustomSubclassException().toErrorInfo().errorCode.assert().isEqualTo("CUSTOM_EXCEPTION")
    }

    @Test
    fun `should register replace lookup and unregister manual converter`() {
        val firstConverter = ErrorInfoConverter<Throwable> { ErrorInfo.of("FIRST_MANUAL") }
        val secondConverter = ErrorInfoConverter<Throwable> { ErrorInfo.of("SECOND_MANUAL") }

        ErrorInfoConverterRegistrar.unregister(ManualConverterException::class.java)
        try {
            ErrorInfoConverterRegistrar.register(ManualConverterException::class.java, firstConverter).assert()
                .isNull()
            ErrorInfoConverterRegistrar.get(ManualConverterSubclassException::class.java).assert()
                .isSameAs(firstConverter)
            ErrorInfoConverterRegistrar.register(ManualConverterException::class.java, secondConverter).assert()
                .isSameAs(firstConverter)
            ManualConverterSubclassException().toErrorInfo().errorCode.assert().isEqualTo("SECOND_MANUAL")
        } finally {
            ErrorInfoConverterRegistrar.unregister(ManualConverterException::class.java)
        }
    }

    @Test
    fun `should materialize error info capable exceptions with default converter`() {
        val errorInfo = ErrorInfoCapableException().toErrorInfo()

        errorInfo.errorCode.assert().isEqualTo("CAPABLE_ERROR")
        errorInfo.errorMsg.assert().isEqualTo("capable message")
    }

    @ParameterizedTest
    @MethodSource("defaultConverterCases")
    fun `should fall back to default converter table when no converter is registered`(
        error: Throwable,
        expectedErrorCode: String
    ) {
        error.toErrorInfo().let {
            it.errorCode.assert().isEqualTo(expectedErrorCode)
            it.errorMsg.assert().isEqualTo(error.message)
        }
    }

    companion object {
        @JvmStatic
        fun defaultConverterCases(): Stream<Arguments> =
            Stream.of(
                Arguments.arguments(IllegalArgumentException("bad input"), ErrorCodes.ILLEGAL_ARGUMENT),
                Arguments.arguments(IllegalStateException("bad state"), ErrorCodes.ILLEGAL_STATE),
                Arguments.arguments(TimeoutException("timeout"), ErrorCodes.REQUEST_TIMEOUT),
                Arguments.arguments(FileNotFoundException("missing file"), ErrorCodes.NOT_FOUND),
                Arguments.arguments(RuntimeException("generic"), ErrorCodes.BAD_REQUEST),
            )
    }
}

open class CustomException : RuntimeException()

class CustomSubclassException : CustomException()

object CustomExceptionErrorInfoConverter : ErrorInfoConverter<CustomException> {
    override fun convert(error: CustomException): ErrorInfo = ErrorInfo.of("CUSTOM_EXCEPTION")
}

class CustomExceptionErrorInfoConverterFactory : AbstractErrorInfoConverterFactory<CustomException>() {
    override fun create(): ErrorInfoConverter<CustomException> = CustomExceptionErrorInfoConverter
}

private open class ManualConverterException : RuntimeException()

private class ManualConverterSubclassException : ManualConverterException()

private class ErrorInfoCapableException : RuntimeException("ignored runtime message"), ErrorInfoCapable {
    override val errorInfo: ErrorInfo = ErrorInfo.of("CAPABLE_ERROR", "capable message")
}
