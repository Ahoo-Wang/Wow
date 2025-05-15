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

package me.ahoo.wow.webflux.exception

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.BindingError
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.toErrorInfo
import org.junit.jupiter.api.Test
import org.springframework.context.MessageSourceResolvable
import org.springframework.core.KotlinReflectionParameterNameDiscoverer
import org.springframework.core.MethodParameter
import org.springframework.validation.method.MethodValidationResult
import org.springframework.validation.method.ParameterValidationResult
import org.springframework.web.method.annotation.HandlerMethodValidationException

class HandlerMethodValidationExceptionConverterTest {
    @Test
    fun convert() {
        val method = HandlerMethodValidationExceptionConverterTest::class.java.declaredMethods.first {
            it.name == "mockMethod"
        }

        val methodParameter = MethodParameter(method, 0)
        methodParameter.initParameterNameDiscovery(KotlinReflectionParameterNameDiscoverer())
        val messageSourceResolvable = mockk<MessageSourceResolvable> {
            every { defaultMessage } returns "error"
        }

        val methodValidationResult = mockk<MethodValidationResult> {
            every { parameterValidationResults } returns listOf(
                ParameterValidationResult(
                    methodParameter,
                    "file",
                    listOf(messageSourceResolvable),
                    null, null, null
                ) { _, _ -> IllegalArgumentException() }
            )
            every { isForReturnValue } returns false
        }
        val error = HandlerMethodValidationException(methodValidationResult)
        val errorInfo = error.toErrorInfo()
        errorInfo.errorCode.assert().isEqualTo(ErrorCodes.ILLEGAL_ARGUMENT)
        errorInfo.errorMsg.assert().isEqualTo("Parameter binding validation failed.")
        errorInfo.bindingErrors.assert().isNotEmpty()
            .first().isEqualTo(BindingError("parameter", "error"))
    }

    fun mockMethod(@Suppress("UNUSED_PARAMETER") parameter: String) = Unit
}
