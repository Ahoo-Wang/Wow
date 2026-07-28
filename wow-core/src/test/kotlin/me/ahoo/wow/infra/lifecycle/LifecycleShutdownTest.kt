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

package me.ahoo.wow.infra.lifecycle

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.concurrent.atomic.AtomicBoolean

class LifecycleShutdownTest {

    @Test
    fun `failure callback cannot interrupt remaining graceful stops`() {
        val calls = mutableListOf<String>()
        val firstFailure = IllegalStateException("first")
        val callbackFailure = IllegalArgumentException("callback")
        val lastFailure = UnsupportedOperationException("last")
        val callbackFailed = AtomicBoolean()

        StepVerifier.create(
            stopAllGracefully(
                stopActions = listOf(
                    {
                        calls += "first"
                        Mono.error(firstFailure)
                    },
                    {
                        calls += "second"
                        Mono.empty()
                    },
                    {
                        calls += "third"
                        Mono.error(lastFailure)
                    },
                ),
                onFailure = {
                    if (callbackFailed.compareAndSet(false, true)) {
                        throw callbackFailure
                    }
                },
            ),
        )
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(firstFailure)
                error.suppressedExceptions.assert().containsExactly(
                    callbackFailure,
                    lastFailure,
                )
            }
            .verify()

        calls.assert().containsExactly("first", "second", "third")
    }
}
