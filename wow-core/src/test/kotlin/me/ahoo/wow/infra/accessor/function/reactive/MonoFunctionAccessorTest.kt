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
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier

class MonoFunctionAccessorTest {
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

    @Test
    fun `invokeSingle should use simple accessor for Mono return values`() {
        val accessor = ReactiveAccessorFixture::monoEcho.toMonoFunctionAccessor<ReactiveAccessorFixture, String>()

        accessor.assert().isInstanceOf(SimpleMonoFunctionAccessor::class.java)
        StepVerifier.create(accessor.invokeSingle(fixture, "wow"))
            .expectNext("mono wow")
            .verifyComplete()
    }

    @Test
    fun `invokeSingle should use sync accessor`() {
        val accessor = ReactiveAccessorFixture::syncEcho.toMonoFunctionAccessor<ReactiveAccessorFixture, String>()

        accessor.assert().isInstanceOf(SyncMonoFunctionAccessor::class.java)
        StepVerifier.create(accessor.invokeSingle(fixture, "wow"))
            .expectNext("sync wow")
            .verifyComplete()
    }

    @Test
    fun `invokeSingle should collect Flux`() {
        val accessor = ReactiveAccessorFixture::fluxEcho
            .toMonoFunctionAccessor<ReactiveAccessorFixture, List<String>>()

        accessor.assert().isInstanceOf(FluxMonoFunctionAccessor::class.java)
        StepVerifier.create(accessor.invokeSingle(fixture, "wow"))
            .expectNext(listOf("flux wow", "flux wow"))
            .verifyComplete()
    }

    @Test
    fun `invokeSingle should collect Publisher`() {
        val accessor = ReactiveAccessorFixture::publisherEcho
            .toMonoFunctionAccessor<ReactiveAccessorFixture, List<String>>()

        accessor.assert().isInstanceOf(PublisherMonoFunctionAccessor::class.java)
        StepVerifier.create(accessor.invokeSingle(fixture, "wow"))
            .expectNext(listOf("publisher wow", "publisher wow"))
            .verifyComplete()
    }

    @Test
    fun `invokeSingle should preserve blocking accessor wrapper`() {
        val accessor = ReactiveAccessorFixture::blockingEcho.toMonoFunctionAccessor<ReactiveAccessorFixture, String>()

        accessor.assert().isInstanceOf(BlockingMonoFunctionAccessor::class.java)
        StepVerifier.create(accessor.invokeSingle(fixture, "wow"))
            .expectNext("blocking wow")
            .verifyComplete()
    }

    @Test
    fun `blocking invokeSingle should delegate to wrapped invokeSingle`() {
        val delegate = RecordingMonoFunctionAccessor()
        val accessor = BlockingMonoFunctionAccessor(delegate, Schedulers.immediate())

        StepVerifier.create(accessor.invokeSingle(fixture, "wow"))
            .expectNext("single wow")
            .verifyComplete()

        delegate.invokeSingleCount.assert().isEqualTo(1)
        delegate.invokeArrayCount.assert().isEqualTo(0)
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

    fun monoEcho(value: String): Mono<String> = Mono.just("mono $value")

    fun syncEcho(value: String): String = "sync $value"

    fun fluxEcho(value: String): Flux<String> = Flux.just("flux $value", "flux $value")

    fun publisherEcho(value: String): Publisher<String> = Flux.just("publisher $value", "publisher $value")

    @Blocking
    fun blockingEcho(value: String): String = "blocking $value"
}

private class RecordingMonoFunctionAccessor : MonoFunctionAccessor<ReactiveAccessorFixture, Mono<String>> {
    var invokeArrayCount: Int = 0
    var invokeSingleCount: Int = 0

    override val function = ReactiveAccessorFixture::syncEcho

    override fun invoke(
        target: ReactiveAccessorFixture,
        args: Array<Any?>
    ): Mono<String> {
        invokeArrayCount++
        return Mono.just("array ${args[0]}")
    }

    override fun invokeSingle(
        target: ReactiveAccessorFixture,
        arg: Any?
    ): Mono<String> {
        invokeSingleCount++
        return Mono.just("single $arg")
    }
}
