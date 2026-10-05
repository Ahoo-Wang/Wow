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

package me.ahoo.wow.runtime

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.kotlin.test.test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeoutException

class RuntimeResourcesTest {
    @Test
    fun `resources are resolved at prepare, once each, without the no-op resource`() {
        val resource = RecordingResource("store")
        var resolutions = 0
        val resources = RuntimeResources {
            resolutions++
            listOf(resource, RuntimeResource.NONE, resource)
        }
        resolutions.assert().isZero()

        resources.prepare(NoopRuntimeContext).block()

        resolutions.assert().isOne()
        resources.resolved.assert().containsExactly(resource)
    }

    @Test
    fun `graceful stop flushes every resource and reports the first failure after all of them`() {
        val calls = CopyOnWriteArrayList<String>()
        val failure = IllegalStateException("flush")
        val resources = RuntimeResources(
            listOf(
                RecordingResource("failing", calls, Mono.error(failure)),
                RecordingResource("store", calls),
            ),
        )
        resources.prepare(NoopRuntimeContext).block()

        resources.stopGracefully().test().expectErrorSatisfies { it.assert().isSameAs(failure) }.verify()

        calls.assert().containsExactlyInAnyOrder("stop:failing", "stop:store")
    }

    @Test
    fun `resources are flushed concurrently, so one slow resource does not hold another`() {
        val calls = CopyOnWriteArrayList<String>()
        val slow = Sinks.empty<Void>()
        val resources = RuntimeResources(
            listOf(
                RecordingResource("slow", calls, slow.asMono()),
                RecordingResource("store", calls, Mono.fromRunnable { calls.add("flushed:store") }),
            ),
        )
        resources.prepare(NoopRuntimeContext).block()

        val stop = resources.stopGracefully().toFuture()

        assertThrows<TimeoutException> { stop.get(200, java.util.concurrent.TimeUnit.MILLISECONDS) }
        calls.assert().contains("flushed:store")
        slow.tryEmitEmpty()
        stop.get(1, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Test
    fun `force stop reaches every resource and rethrows the first failure`() {
        val calls = CopyOnWriteArrayList<String>()
        val first = IllegalStateException("first")
        val resources = RuntimeResources(
            listOf(
                RecordingResource("a", calls, forceFailure = first),
                RecordingResource("b", calls, forceFailure = IllegalStateException("second")),
                RecordingResource("c", calls),
            ),
        )
        resources.forceStop()
        resources.prepare(NoopRuntimeContext).block()

        val thrown = assertThrows<IllegalStateException> { resources.forceStop() }

        thrown.assert().isSameAs(first)
        thrown.suppressed.single().message.assert().isEqualTo("second")
        calls.assert().containsExactly("force:a", "force:b", "force:c")
    }

    @Test
    fun `the runtime flushes resources after its components, within its one deadline`() {
        val calls = CopyOnWriteArrayList<String>()
        val component = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext): Mono<Void> = Mono.empty()

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> = Mono.fromRunnable { calls.add("stop:dispatcher") }

            override fun forceStop() {
                calls.add("force:dispatcher")
            }
        }
        val hanging = RecordingResource("writer", calls, Mono.never())
        val runtime = WowRuntime(
            components = listOf(RuntimeResources(listOf(hanging)), component),
            shutdownTimeout = Duration.ofMillis(200),
            shutdownQuietPeriod = Duration.ZERO,
        )
        runtime.start().block()

        runtime.stopGracefully().test().expectError(TimeoutException::class.java).verify(Duration.ofSeconds(2))

        calls.assert().containsSubsequence("stop:dispatcher", "stop:writer", "force:writer")
    }

    private class RecordingResource(
        private val name: String,
        private val calls: MutableList<String> = CopyOnWriteArrayList(),
        private val stop: Mono<Void> = Mono.empty(),
        private val forceFailure: Throwable? = null,
    ) : RuntimeResource {
        override fun stopGracefully(): Mono<Void> = Mono.defer {
            calls.add("stop:$name")
            stop
        }

        override fun forceStop() {
            calls.add("force:$name")
            forceFailure?.let { throw it }
        }
    }

    private object NoopRuntimeContext : RuntimeContext {
        override fun reportFailure(error: Throwable) = Unit

        override fun tryAcquire(): RuntimeActivity? = RuntimeActivity {}
    }
}
