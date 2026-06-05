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

package me.ahoo.wow.reactor

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.concurrent.atomic.AtomicBoolean

class MonosBehaviorTest {

    @Test
    fun `thenDefer creates the next mono only after successful completion`() {
        val invoked = AtomicBoolean(false)
        val mono = Mono.just("source").thenDefer {
            invoked.set(true)
            Mono.just("next")
        }

        invoked.get().assert().isFalse()

        StepVerifier.create(mono)
            .expectNext("next")
            .verifyComplete()
        invoked.get().assert().isTrue()
    }

    @Test
    fun `thenDefer does not invoke supplier when source fails`() {
        val invoked = AtomicBoolean(false)
        val error = IllegalStateException("failed")

        StepVerifier.create(
            Mono.error<String>(error)
                .thenDefer {
                    invoked.set(true)
                    Mono.just("next")
                },
        )
            .expectErrorSatisfies { it.assert().isSameAs(error) }
            .verify()

        invoked.get().assert().isFalse()
    }

    @Test
    fun `thenRunnable runs after source completion`() {
        val invoked = AtomicBoolean(false)

        StepVerifier.create(Mono.empty<String>().thenRunnable { invoked.set(true) })
            .verifyComplete()

        invoked.get().assert().isTrue()
    }
}
