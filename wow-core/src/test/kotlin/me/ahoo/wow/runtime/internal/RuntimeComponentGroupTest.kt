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

package me.ahoo.wow.runtime.internal

import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.RuntimeComponent
import me.ahoo.wow.runtime.RuntimeContext
import me.ahoo.wow.runtime.RuntimeOwnershipClaim
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RuntimeComponentGroupTest {

    @Test
    fun `default component ownership is exclusive and rolls back transactionally`() {
        val component = DefaultOwnershipComponent()
        val claimFailure = IllegalStateException("claim")
        val failing = object : DefaultOwnershipComponent() {
            override fun claimRuntimeOwnership(): RuntimeOwnershipClaim {
                throw claimFailure
            }
        }

        assertThrows<IllegalStateException> {
            RuntimeComponentGroup.claim(listOf(component, failing), reportFailure = {})
        }
            .assert()
            .isSameAs(claimFailure)

        RuntimeComponentGroup.claim(listOf(component), reportFailure = {})
        assertThrows<IllegalStateException> {
            RuntimeComponentGroup.claim(listOf(component), reportFailure = {})
        }
            .message
            .assert()
            .contains("runtime ownership is already EXTERNAL")
    }

    @Test
    fun `duplicate owner-bound component rolls back the complete claim transaction`() {
        val commits = AtomicInteger()
        val rollbacks = AtomicInteger()
        val ownerView = RecordingComponent("owner", mutableListOf())

        fun claimant(name: String): RuntimeComponent =
            object : RuntimeComponent {
                override fun claimRuntimeOwnership(): RuntimeOwnershipClaim =
                    object : RuntimeOwnershipClaim {
                        override val component: RuntimeComponent = ownerView

                        override fun commit() {
                            commits.incrementAndGet()
                        }

                        override fun rollback() {
                            rollbacks.incrementAndGet()
                        }
                    }

                override fun prepare(runtimeContext: RuntimeContext) = Unit

                override fun start() = Unit

                override fun stopGracefully(): Mono<Void> = Mono.empty()

                override fun forceStop() = Unit

                override fun toString(): String = name
            }

        assertThrows<IllegalArgumentException> {
            RuntimeComponentGroup.claim(
                listOf(claimant("first"), claimant("second")),
                reportFailure = {},
            )
        }

        commits.get().assert().isZero()
        rollbacks.get().assert().isEqualTo(2)
    }

    @Test
    fun `component resolution failure rolls back every acquired claim in reverse order`() {
        val claimFailure = IllegalStateException("component")
        val calls = mutableListOf<String>()
        val first = ClaimRecordingComponent("first", calls)
        val second = ClaimRecordingComponent(
            name = "second",
            calls = calls,
            componentFailure = claimFailure,
        )

        val thrown = assertThrows<IllegalStateException> {
            RuntimeComponentGroup.claim(listOf(first, second), reportFailure = {})
        }

        thrown.assert().isSameAs(claimFailure)
        calls.assert().containsExactly(
            "claim:first",
            "resolve:first",
            "claim:second",
            "resolve:second",
            "rollback:second",
            "rollback:first",
        )
        first.isClaimed.assert().isFalse()
        second.isClaimed.assert().isFalse()

        RuntimeComponentGroup.claim(listOf(first), reportFailure = {})
        first.isClaimed.assert().isTrue()
    }

    @Test
    fun `rollback failure cannot mask claim failure or skip earlier claims`() {
        val claimFailure = IllegalStateException("component")
        val rollbackFailure = IllegalArgumentException("rollback")
        val calls = mutableListOf<String>()
        val first = ClaimRecordingComponent("first", calls)
        val second = ClaimRecordingComponent(
            name = "second",
            calls = calls,
            rollbackFailure = rollbackFailure,
        )
        val third = ClaimRecordingComponent(
            name = "third",
            calls = calls,
            componentFailure = claimFailure,
        )

        val thrown = assertThrows<IllegalStateException> {
            RuntimeComponentGroup.claim(listOf(first, second, third), reportFailure = {})
        }

        thrown.assert().isSameAs(claimFailure)
        thrown.suppressedExceptions.assert().containsExactly(rollbackFailure)
        calls.takeLast(3).assert().containsExactly(
            "rollback:third",
            "rollback:second",
            "rollback:first",
        )
        first.isClaimed.assert().isFalse()
        second.isClaimed.assert().isFalse()
        third.isClaimed.assert().isFalse()
    }

    @Test
    fun `owner admission gate can reject lifecycle entry atomically`() {
        val calls = mutableListOf<String>()
        val group = RuntimeComponentGroup.claim(
            listOf(RecordingComponent("component", calls)),
            reportFailure = {},
        )

        val prepared = group.prepare(
            runtimeContext = DefaultRuntimeContext(),
            admissionGate = { false },
        )

        prepared.assert().isFalse()
        calls.assert().isEmpty()
    }

    @Test
    fun `group provides a readiness barrier and reverse cleanup`() {
        val calls = mutableListOf<String>()
        val group = RuntimeComponentGroup.claim(
            listOf(
                RecordingComponent("first", calls),
                RecordingComponent("second", calls),
            ),
            reportFailure = {},
        )

        group.prepare(DefaultRuntimeContext()).assert().isTrue()
        group.start().assert().isTrue()
        StepVerifier.create(group.stopGracefully()).verifyComplete()

        calls.assert().containsExactly(
            "prepare:first",
            "prepare:second",
            "start:first",
            "start:second",
            "stop:second",
            "stop:first",
        )
    }

    @Test
    fun `force stop covers every claimed component before preparation`() {
        val calls = mutableListOf<String>()
        val group = RuntimeComponentGroup.claim(
            listOf(
                RecordingComponent("first", calls),
                RecordingComponent("second", calls),
            ),
            reportFailure = {},
        )

        group.forceStop()

        calls.assert().containsExactly("force:second", "force:first")
        group.prepare(DefaultRuntimeContext()).assert().isFalse()
    }

    @Test
    fun `group force stop prevents a physically uncancelled graceful chain from advancing`() {
        val calls = mutableListOf<String>()
        val stopEntered = CountDownLatch(1)
        val stopGate = Sinks.empty<Void>()
        val first = RecordingComponent("first", calls)
        val second = RecordingComponent(
            name = "second",
            calls = calls,
            stopAction = {
                stopEntered.countDown()
                stopGate.asMono()
            },
        )
        val group = RuntimeComponentGroup.claim(listOf(first, second), reportFailure = {})
        group.prepare(DefaultRuntimeContext()).assert().isTrue()
        val gracefulStop = group.stopGracefully().toFuture()

        stopEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
        group.forceStop()
        stopGate.tryEmitEmpty().orThrow()
        gracefulStop.get(1, TimeUnit.SECONDS)

        calls.assert().contains("stop:second")
        calls.assert().doesNotContain("stop:first")
    }

    private class RecordingComponent(
        private val name: String,
        private val calls: MutableList<String>,
        private val stopAction: () -> Mono<Void> = { Mono.empty() },
    ) : RuntimeComponent {
        override fun claimRuntimeOwnership(): RuntimeOwnershipClaim =
            RuntimeOwnershipClaim.shared(this)

        override fun prepare(runtimeContext: RuntimeContext) {
            calls += "prepare:$name"
        }

        override fun start() {
            calls += "start:$name"
        }

        override fun stopGracefully(): Mono<Void> =
            Mono.defer {
                calls += "stop:$name"
                stopAction()
            }

        override fun forceStop() {
            calls += "force:$name"
        }
    }

    private open class DefaultOwnershipComponent : RuntimeComponent {
        override fun prepare(runtimeContext: RuntimeContext) = Unit

        override fun start() = Unit

        override fun stopGracefully(): Mono<Void> = Mono.empty()

        override fun forceStop() = Unit
    }

    private class ClaimRecordingComponent(
        private val name: String,
        private val calls: MutableList<String>,
        private val componentFailure: RuntimeException? = null,
        private val rollbackFailure: RuntimeException? = null,
    ) : RuntimeComponent {
        var isClaimed: Boolean = false
            private set

        override fun claimRuntimeOwnership(): RuntimeOwnershipClaim {
            check(!isClaimed)
            isClaimed = true
            calls += "claim:$name"
            val ownerComponent = this
            return object : RuntimeOwnershipClaim {
                override val component: RuntimeComponent
                    get() {
                        calls += "resolve:$name"
                        componentFailure?.let { throw it }
                        return ownerComponent
                    }

                override fun commit() {
                    calls += "commit:$name"
                }

                override fun rollback() {
                    calls += "rollback:$name"
                    isClaimed = false
                    rollbackFailure?.let { throw it }
                }
            }
        }

        override fun prepare(runtimeContext: RuntimeContext) = Unit

        override fun start() = Unit

        override fun stopGracefully(): Mono<Void> = Mono.empty()

        override fun forceStop() = Unit
    }
}
