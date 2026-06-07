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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class NotFoundResourceExceptionTest {

    @Test
    fun `should expose not found error info from exception`() {
        val cause = RuntimeException("cause")
        val exception = NotFoundResourceException("missing cart", cause)

        exception.errorCode.assert().isEqualTo(ErrorCodes.NOT_FOUND)
        exception.errorMsg.assert().isEqualTo("missing cart")
        exception.message.assert().isEqualTo("missing cart")
        exception.cause.assert().isSameAs(cause)
    }

    @Test
    fun `should throw not found with error data when nullable value is null`() {
        val cause = RuntimeException("cause")

        val exception = assertThrows<NotFoundResourceException> {
            (null as String?).throwNotFoundIfNull("missing value", cause)
        }

        exception.errorCode.assert().isEqualTo(ErrorCodes.NOT_FOUND)
        exception.errorMsg.assert().isEqualTo("missing value")
        exception.cause.assert().isSameAs(cause)
    }

    @Test
    fun `should turn empty mono into not found error signal`() {
        StepVerifier.create(Mono.empty<String>().throwNotFoundIfEmpty("missing mono"))
            .expectErrorSatisfies {
                (it is NotFoundResourceException).assert().isTrue()
                val exception = it as NotFoundResourceException
                exception.errorCode.assert().isEqualTo(ErrorCodes.NOT_FOUND)
                exception.errorMsg.assert().isEqualTo("missing mono")
            }
            .verify()
    }

    @Test
    fun `should turn empty flux into not found error signal`() {
        StepVerifier.create(Flux.empty<String>().throwNotFoundIfEmpty("missing flux"))
            .expectErrorSatisfies {
                (it is NotFoundResourceException).assert().isTrue()
                val exception = it as NotFoundResourceException
                exception.errorCode.assert().isEqualTo(ErrorCodes.NOT_FOUND)
                exception.errorMsg.assert().isEqualTo("missing flux")
            }
            .verify()
    }
}
