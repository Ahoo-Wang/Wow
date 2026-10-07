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
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class SuspendableDemandFluxTest {

    @Test
    fun `forwards demand until suspended and still delivers what was requested`() {
        val source = Sinks.many().unicast().onBackpressureBuffer<Int>()
        val requested = AtomicLong()
        val flux = SuspendableDemandFlux(source.asFlux().doOnRequest(requested::addAndGet))

        StepVerifier.create(flux, 2)
            .then { source.tryEmitNext(1).orThrow() }
            .expectNext(1)
            .then { flux.suspendDemand() }
            .thenRequest(5)
            .then {
                source.tryEmitNext(2).orThrow()
                source.tryEmitNext(3).orThrow()
            }
            .expectNext(2)
            .expectNoEvent(Duration.ofMillis(50))
            .thenCancel()
            .verify()

        requested.get().assert().isEqualTo(2)
    }

    @Test
    fun `forwards completion, errors and cancellation`() {
        StepVerifier.create(SuspendableDemandFlux(Flux.just(1, 2)))
            .expectNext(1, 2)
            .verifyComplete()

        val failure = IllegalStateException("source")
        StepVerifier.create(SuspendableDemandFlux(Flux.error<Int>(failure)))
            .verifyErrorSatisfies { it.assert().isSameAs(failure) }

        val cancelled = AtomicBoolean()
        StepVerifier.create(SuspendableDemandFlux(Flux.never<Int>().doOnCancel { cancelled.set(true) }))
            .thenCancel()
            .verify()
        cancelled.get().assert().isTrue()
    }
}
