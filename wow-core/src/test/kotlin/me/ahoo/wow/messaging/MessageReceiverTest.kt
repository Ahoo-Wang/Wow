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

package me.ahoo.wow.messaging

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import java.util.concurrent.atomic.AtomicInteger

class MessageReceiverTest {

    @Test
    fun `messages support exactly one subscription`() {
        val receiver = MessageReceiver(Flux.just(1))

        StepVerifier.create(receiver.messages)
            .expectNext(1)
            .verifyComplete()
        StepVerifier.create(receiver.messages)
            .expectErrorSatisfies { error ->
                error.assert()
                    .isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("exactly one")
            }
            .verify()
    }

    @Test
    fun `mapping messages preserves readiness and processing admission`() {
        val readiness = Sinks.empty<Void>()
        val processingAdmissions = AtomicInteger()
        val receiver = MessageReceiver(
            messages = Flux.just(1),
            readiness = readiness.asMono(),
            processingAdmission = processingAdmissions::incrementAndGet,
        ).mapMessages { messages ->
            messages.map(Int::toString)
        }

        StepVerifier.create(receiver.messages)
            .expectNext("1")
            .verifyComplete()
        StepVerifier.create(receiver.readiness)
            .then { readiness.tryEmitEmpty().orThrow() }
            .verifyComplete()

        receiver.openProcessing()
        receiver.openProcessing()
        processingAdmissions.get().assert().isOne()
    }
}
