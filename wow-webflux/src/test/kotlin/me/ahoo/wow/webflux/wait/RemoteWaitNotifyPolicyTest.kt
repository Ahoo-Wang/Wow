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

package me.ahoo.wow.webflux.wait

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.io.IOException
import java.net.URI

class RemoteWaitNotifyPolicyTest {

    @Test
    fun `default policy should not switch subscription thread`() {
        val callerThreadName = Thread.currentThread().name

        RemoteWaitNotifyPolicy()
            .apply(Mono.fromCallable { Thread.currentThread().name })
            .test()
            .expectNext(callerThreadName)
            .verifyComplete()
    }

    @Test
    fun `should classify transient web client failures only`() {
        WebClientRequestException(
            IOException("connection reset"),
            HttpMethod.POST,
            URI.create("http://localhost/command/wait"),
            HttpHeaders.EMPTY,
        ).isRetryableRemoteWaitFailure().assert().isTrue()
        WebClientResponseException.create(
            HttpStatus.SERVICE_UNAVAILABLE.value(),
            HttpStatus.SERVICE_UNAVAILABLE.reasonPhrase,
            HttpHeaders.EMPTY,
            ByteArray(0),
            null,
        ).isRetryableRemoteWaitFailure().assert().isTrue()
        WebClientResponseException.create(
            HttpStatus.BAD_REQUEST.value(),
            HttpStatus.BAD_REQUEST.reasonPhrase,
            HttpHeaders.EMPTY,
            ByteArray(0),
            null,
        ).isRetryableRemoteWaitFailure().assert().isFalse()
    }
}
