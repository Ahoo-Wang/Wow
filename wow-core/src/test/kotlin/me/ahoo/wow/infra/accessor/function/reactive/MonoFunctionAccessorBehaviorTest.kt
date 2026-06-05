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

package me.ahoo.wow.infra.accessor.function.reactive

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.Blocking
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class MonoFunctionAccessorBehaviorTest {
    private val fixture = ReactiveAccessorFixture()

    @Test
    fun `should use simple accessor for Mono return values`() {
        val accessor = ReactiveAccessorFixture::monoValue.toMonoFunctionAccessor<ReactiveAccessorFixture, String>()

        accessor.assert().isInstanceOf(SimpleMonoFunctionAccessor::class.java)
        StepVerifier.create(accessor.invoke(fixture))
            .expectNext("mono")
            .verifyComplete()
    }

    @Test
    fun `should use sync accessor for non reactive return values`() {
        val accessor = ReactiveAccessorFixture::syncValue.toMonoFunctionAccessor<ReactiveAccessorFixture, String>()

        accessor.assert().isInstanceOf(SyncMonoFunctionAccessor::class.java)
        StepVerifier.create(accessor.invoke(fixture))
            .expectNext("sync")
            .verifyComplete()
    }

    @Test
    fun `should collect Flux return values into a list`() {
        val accessor = ReactiveAccessorFixture::fluxValues
            .toMonoFunctionAccessor<ReactiveAccessorFixture, List<String>>()

        accessor.assert().isInstanceOf(FluxMonoFunctionAccessor::class.java)
        StepVerifier.create(accessor.invoke(fixture))
            .expectNext(listOf("flux-1", "flux-2"))
            .verifyComplete()
    }

    @Test
    fun `should collect Publisher return values into a list`() {
        val accessor = ReactiveAccessorFixture::publisherValues
            .toMonoFunctionAccessor<ReactiveAccessorFixture, List<String>>()

        accessor.assert().isInstanceOf(PublisherMonoFunctionAccessor::class.java)
        StepVerifier.create(accessor.invoke(fixture))
            .expectNext(listOf("publisher-1", "publisher-2"))
            .verifyComplete()
    }

    @Test
    fun `should adapt suspend return values into Mono`() {
        val accessor = ReactiveAccessorFixture::suspendValue.toMonoFunctionAccessor<ReactiveAccessorFixture, String>()

        accessor.assert().isInstanceOf(SuspendMonoFunctionAccessor::class.java)
        StepVerifier.create(accessor.invoke(fixture))
            .expectNext("suspend")
            .verifyComplete()
    }

    @Test
    fun `should collect Flow return values into a list`() {
        val accessor = ReactiveAccessorFixture::flowValues
            .toMonoFunctionAccessor<ReactiveAccessorFixture, List<String>>()

        accessor.assert().isInstanceOf(FlowMonoFunctionAccessor::class.java)
        StepVerifier.create(accessor.invoke(fixture))
            .expectNext(listOf("flow-1", "flow-2"))
            .verifyComplete()
    }

    @Test
    fun `should wrap blocking functions with blocking accessor`() {
        val accessor = ReactiveAccessorFixture::blockingValue.toMonoFunctionAccessor<ReactiveAccessorFixture, String>()

        accessor.assert().isInstanceOf(BlockingMonoFunctionAccessor::class.java)
        StepVerifier.create(accessor.invoke(fixture))
            .expectNext("blocking")
            .verifyComplete()
    }
}

private class ReactiveAccessorFixture {
    private val monoResult = "mono"
    private val syncResult = "sync"
    private val suspendResult = "suspend"
    private val blockingResult = "blocking"

    fun monoValue(): Mono<String> = Mono.just(monoResult)

    fun syncValue(): String = syncResult

    fun fluxValues(): Flux<String> = Flux.just("flux-1", "flux-2")

    fun publisherValues(): Publisher<String> = Flux.just("publisher-1", "publisher-2")

    suspend fun suspendValue(): String = suspendResult

    fun flowValues(): Flow<String> = flow {
        emit("flow-1")
        emit("flow-2")
    }

    @Blocking
    fun blockingValue(): String = blockingResult
}
