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

package me.ahoo.wow.messaging.dispatcher

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier

class SafeSubscriberTest {

    @Test
    fun `safeOnNext receives emitted values`() {
        val received = Sinks.one<String>()
        val subscriber = object : SafeSubscriber<String>() {
            override val name: String = "safe-subscriber"

            override fun safeOnNext(value: String) {
                received.tryEmitValue(value).orThrow()
            }
        }

        Mono.just("message").subscribe(subscriber)

        StepVerifier.create(received.asMono())
            .expectNext("message")
            .verifyComplete()
    }

    @Test
    fun `safeOnNext errors are delegated to safeOnNextError`() {
        val handled = Sinks.one<Pair<String, Throwable>>()
        val error = IllegalStateException("boom")
        val subscriber = object : SafeSubscriber<String>() {
            override val name: String = "safe-subscriber"

            override fun safeOnNext(value: String) {
                throw error
            }

            override fun safeOnNextError(value: String, throwable: Throwable) {
                handled.tryEmitValue(value to throwable).orThrow()
            }
        }

        Mono.just("message").subscribe(subscriber)

        StepVerifier.create(handled.asMono())
            .assertNext {
                it.first.assert().isEqualTo("message")
                it.second.assert().isSameAs(error)
            }
            .verifyComplete()
    }

    @Test
    fun `source errors reach hookOnError`() {
        val sourceError = IllegalArgumentException("source")
        val handled = Sinks.one<Throwable>()
        val subscriber = object : SafeSubscriber<String>() {
            override val name: String = "safe-subscriber"

            override fun hookOnError(throwable: Throwable) {
                handled.tryEmitValue(throwable).orThrow()
            }
        }

        Mono.error<String>(sourceError).subscribe(subscriber)

        StepVerifier.create(handled.asMono())
            .expectNext(sourceError)
            .verifyComplete()
    }
}
