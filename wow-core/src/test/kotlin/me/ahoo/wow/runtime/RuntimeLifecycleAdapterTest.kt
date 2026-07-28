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
import me.ahoo.wow.infra.lifecycle.Lifecycle
import me.ahoo.wow.runtime.internal.DefaultRuntimeContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import java.time.Duration

class RuntimeLifecycleAdapterTest {

    @Test
    fun `same adapter instance cannot be claimed by two runtimes`() {
        val adapter = newAdapter()
        WowRuntime(listOf(adapter), SHUTDOWN_TIMEOUT, Duration.ZERO)

        val thrown = assertThrows<IllegalStateException> {
            WowRuntime(listOf(adapter), SHUTDOWN_TIMEOUT, Duration.ZERO)
        }

        thrown.message.assert().contains("ownership")
    }

    @Test
    fun `different adapters for the same delegate cannot be claimed by one runtime`() {
        val delegate = newDelegate()
        val first = newAdapter(delegate)
        val second = newAdapter(delegate)

        val thrown = assertThrows<IllegalStateException> {
            WowRuntime(listOf(first, second), SHUTDOWN_TIMEOUT, Duration.ZERO)
        }

        thrown.message.assert().contains("ownership")
        val retry = WowRuntime(listOf(first), SHUTDOWN_TIMEOUT, Duration.ZERO)
        retry.start()
        retry.forceStop()
    }

    @Test
    fun `different adapters for the same delegate cannot be claimed by different runtimes`() {
        val delegate = newDelegate()
        val first = newAdapter(delegate)
        val second = newAdapter(delegate)
        val owner = WowRuntime(listOf(first), SHUTDOWN_TIMEOUT, Duration.ZERO)

        val thrown = assertThrows<IllegalStateException> {
            WowRuntime(listOf(second), SHUTDOWN_TIMEOUT, Duration.ZERO)
        }

        thrown.message.assert().contains("ownership")
        owner.forceStop()
    }

    @Test
    fun `adapter ownership description never invokes delegate toString`() {
        val delegate = object : Lifecycle {
            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> = Mono.empty()

            override fun toString(): String = error("user toString must not be invoked")
        }
        val runtime = WowRuntime(
            listOf(newAdapter(delegate)),
            SHUTDOWN_TIMEOUT,
            Duration.ZERO,
        )

        runtime.start()
        runtime.forceStop()
    }

    @Test
    fun `adapter claim is rolled back when a later component claim fails`() {
        val adapter = newAdapter()
        val claimFailure = IllegalStateException("claim")
        val failingComponent = object : RuntimeComponent {
            override val runtimeOwnership: RuntimeOwnership =
                RuntimeOwnership.managed { throw claimFailure }

            override fun prepare(runtimeContext: RuntimeContext) = Unit

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> = Mono.empty()

            override fun forceStop() = Unit
        }

        val thrown = assertThrows<IllegalStateException> {
            WowRuntime(
                listOf(adapter, failingComponent),
                SHUTDOWN_TIMEOUT,
                Duration.ZERO,
            )
        }
        thrown.assert().isSameAs(claimFailure)

        val retry = WowRuntime(listOf(adapter), SHUTDOWN_TIMEOUT, Duration.ZERO)
        retry.start()
        retry.forceStop()
    }

    @Test
    fun `committed adapter ownership remains exclusive after runtime termination`() {
        val adapter = newAdapter()
        val owner = WowRuntime(listOf(adapter), SHUTDOWN_TIMEOUT, Duration.ZERO)
        owner.start()
        owner.forceStop()

        val thrown = assertThrows<IllegalStateException> {
            WowRuntime(listOf(adapter), SHUTDOWN_TIMEOUT, Duration.ZERO)
        }

        thrown.message.assert().contains("ownership")
    }

    @Test
    fun `direct lifecycle access is rejected after adapter ownership is claimed`() {
        val adapter = newAdapter()
        val owner = WowRuntime(listOf(adapter), SHUTDOWN_TIMEOUT, Duration.ZERO)

        assertThrows<IllegalStateException> {
            adapter.prepare(DefaultRuntimeContext())
        }
        assertThrows<IllegalStateException> {
            adapter.start()
        }
        assertThrows<IllegalStateException> {
            adapter.stopGracefully()
        }
        assertThrows<IllegalStateException> {
            adapter.forceStop()
        }

        owner.forceStop()
    }

    private fun newDelegate(): Lifecycle =
        object : Lifecycle {
            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> = Mono.empty()
        }

    private fun newAdapter(
        delegate: Lifecycle = newDelegate(),
    ): RuntimeLifecycleAdapter =
        RuntimeLifecycleAdapter(
            delegate = delegate,
            forceStopAction = {},
        )

    private companion object {
        val SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(1)
    }
}
