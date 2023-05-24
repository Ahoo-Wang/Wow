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
@file:Suppress("unused")

package me.ahoo.wow.infra.accessor.method.reactive

import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

internal class MonoMethodAccessorFactoryTest {
    @Test
    fun createMono() {
        val methodAccessor = MockAggregate::class.java.getDeclaredMethod(
            "onCommand",
            ChangeStateReturnMono::class.java,
        ).asMonoMethodAccessor<MockAggregate, Any>()
        MatcherAssert.assertThat(
            methodAccessor,
            Matchers.instanceOf(
                SimpleMonoMethodAccessor::class.java,
            ),
        )
    }

    @Test
    fun createFlux() {
        val methodAccessor =
            MockAggregate::class.java.getDeclaredMethod(
                "onCommand",
                ChangeStateReturnFlux::class.java,
            ).asMonoMethodAccessor<MockAggregate, Any>()
        MatcherAssert.assertThat(
            methodAccessor,
            Matchers.instanceOf(
                FluxMonoMethodAccessor::class.java,
            ),
        )
    }

    @Test
    fun createPublisher() {
        val methodAccessor =
            MockAggregate::class.java.getDeclaredMethod(
                "onCommand",
                ChangeStateReturnPublisher::class.java,
            ).asMonoMethodAccessor<MockAggregate, Any>()
        MatcherAssert.assertThat(
            methodAccessor,
            Matchers.instanceOf(
                PublisherMonoMethodAccessor::class.java,
            ),
        )
    }

    @Test
    fun createSync() {
        val methodAccessor =
            MockAggregate::class.java.getDeclaredMethod(
                "onCommand",
                ChangeStateReturnSync::class.java,
            ).asMonoMethodAccessor<MockAggregate, Any>()
        MatcherAssert.assertThat(
            methodAccessor,
            Matchers.instanceOf(
                SyncMonoMethodAccessor::class.java,
            ),
        )
    }

    @Suppress("FunctionOnlyReturningConstant")
    class MockAggregate(private val id: String) {
        private val state: String? = null
        fun id(): String {
            return id
        }

        fun state(): String? {
            return state
        }

        fun onCommand(changeState: ChangeStateReturnMono?): Mono<StateChanged>? {
            return null
        }

        fun onCommand(changeState: ChangeStateReturnFlux?): Flux<StateChanged>? {
            return null
        }

        fun onCommand(changeState: ChangeStateReturnPublisher?): Publisher<StateChanged>? {
            return null
        }

        fun onCommand(changeState: ChangeStateReturnSync?): StateChanged? {
            return null
        }
    }

    class ChangeStateReturnMono(private val state: String) {
        fun state(): String {
            return state
        }
    }

    class ChangeStateReturnFlux(private val state: String) {
        fun state(): String {
            return state
        }
    }

    class ChangeStateReturnPublisher(private val state: String) {
        fun state(): String {
            return state
        }
    }

    class ChangeStateReturnSync(private val state: String) {
        fun state(): String {
            return state
        }
    }

    class StateChanged(private val state: String) {
        fun state(): String {
            return state
        }
    }
}
