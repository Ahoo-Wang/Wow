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

package me.ahoo.wow.runtime.internal.compat

import me.ahoo.wow.runtime.RuntimeComponent
import me.ahoo.wow.runtime.RuntimeContext
import me.ahoo.wow.runtime.RuntimeOwnershipClaim
import me.ahoo.wow.runtime.WowRuntime
import me.ahoo.wow.runtime.internal.RuntimeComponentGroup
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Gives direct dispatcher usage the same runtime ownership semantics as Spring.
 *
 * The adapter avoids making the dispatcher its own runtime component, which
 * would recurse through its public lifecycle methods during cleanup.
 */
internal class StandaloneRuntimeOwner(
    private val prepareAction: (RuntimeContext) -> Unit,
    private val startAction: () -> Unit,
    private val gracefulStopAction: () -> Mono<Void>,
    private val forceStopAction: () -> Unit,
) {
    private sealed interface Ownership {
        data object Unclaimed : Ownership

        data class External(
            val token: Any,
            var committed: Boolean = false,
        ) : Ownership

        data class Standalone(val runtime: WowRuntime) : Ownership

        data class ManualPrepared(val group: RuntimeComponentGroup) : Ownership
    }

    private val ownershipMonitor = Any()
    private var ownership: Ownership = Ownership.Unclaimed
    private val actionComponent = object : RuntimeComponent {
        override fun claimRuntimeOwnership(): RuntimeOwnershipClaim =
            RuntimeOwnershipClaim.shared(this)

        override fun prepare(runtimeContext: RuntimeContext) {
            prepareAction(runtimeContext)
        }

        override fun start() {
            startAction()
        }

        override fun stopGracefully(): Mono<Void> =
            gracefulStopAction()

        override fun forceStop() {
            forceStopAction()
        }
    }

    private fun directRuntime(): WowRuntime =
        synchronized(ownershipMonitor) {
            when (val current = ownership) {
                Ownership.Unclaimed -> {
                    StandaloneRuntimeFactory.create(
                        component = actionComponent,
                    ).also {
                        ownership = Ownership.Standalone(it)
                    }
                }

                is Ownership.Standalone -> current.runtime
                is Ownership.ManualPrepared -> error(
                    "Dispatcher was prepared manually and cannot create a direct WowRuntime.",
                )
                is Ownership.External -> error(
                    "Dispatcher lifecycle is owned by an external WowRuntime.",
                )
            }
        }

    fun claimExternalOwnership(): RuntimeOwnershipClaim {
        val token = Any()
        synchronized(ownershipMonitor) {
            check(ownership === Ownership.Unclaimed) {
                "Dispatcher lifecycle ownership is already ${ownership.description()}."
            }
            ownership = Ownership.External(token)
        }
        return ExternalOwnershipClaim(token)
    }

    fun prepare(runtimeContext: RuntimeContext) {
        val group = synchronized(ownershipMonitor) {
            check(ownership === Ownership.Unclaimed) {
                "Dispatcher lifecycle ownership is already ${ownership.description()}."
            }
            RuntimeComponentGroup.claim(
                components = listOf(actionComponent),
                reportFailure = {},
            ).also { group ->
                ownership = Ownership.ManualPrepared(group)
            }
        }
        check(group.prepare(runtimeContext)) {
            "Dispatcher preparation was cancelled by force-stop."
        }
    }

    fun start() {
        val manualGroup = synchronized(ownershipMonitor) {
            (ownership as? Ownership.ManualPrepared)?.group
        }
        if (manualGroup != null) {
            check(manualGroup.start()) {
                "Dispatcher start was cancelled by force-stop."
            }
        } else {
            directRuntime().start()
        }
    }

    fun stopGracefully(): Mono<Void> {
        val manualGroup = synchronized(ownershipMonitor) {
            (ownership as? Ownership.ManualPrepared)?.group
        }
        return if (manualGroup != null) {
            manualGroup.stopGracefully()
        } else {
            directRuntime().stopGracefully()
        }
    }

    fun forceStop() {
        val manualGroup = synchronized(ownershipMonitor) {
            (ownership as? Ownership.ManualPrepared)?.group
        }
        if (manualGroup != null) {
            manualGroup.forceStop()?.let { failure ->
                throw failure
            }
        } else {
            directRuntime().forceStop()
        }
    }

    private fun requireExternalOwnership(token: Any) {
        synchronized(ownershipMonitor) {
            val current = ownership as? Ownership.External
            check(current?.token === token && current.committed) {
                "Dispatcher runtime ownership is no longer active."
            }
        }
    }

    private fun commitExternalOwnership(token: Any) {
        synchronized(ownershipMonitor) {
            val current = ownership
            if (current is Ownership.External && current.token === token) {
                current.committed = true
            }
        }
    }

    private fun rollbackExternalOwnership(token: Any) {
        synchronized(ownershipMonitor) {
            val current = ownership
            if (
                current is Ownership.External &&
                current.token === token &&
                !current.committed
            ) {
                ownership = Ownership.Unclaimed
            }
        }
    }

    private fun Ownership.description(): String =
        when (this) {
            Ownership.Unclaimed -> "UNCLAIMED"
            is Ownership.External -> "EXTERNAL"
            is Ownership.Standalone -> "STANDALONE"
            is Ownership.ManualPrepared -> "MANUAL_PREPARED"
        }

    private inner class ExternalOwnershipClaim(
        private val token: Any,
    ) : RuntimeOwnershipClaim {
        override val component: RuntimeComponent = ExternalOwnershipComponent(token)

        override fun commit() {
            commitExternalOwnership(token)
        }

        override fun rollback() {
            rollbackExternalOwnership(token)
        }
    }

    private inner class ExternalOwnershipComponent(
        private val token: Any,
    ) : RuntimeComponent {
        override fun claimRuntimeOwnership(): RuntimeOwnershipClaim =
            error("An owner-bound runtime component cannot be claimed again.")

        override fun prepare(runtimeContext: RuntimeContext) {
            requireExternalOwnership(token)
            prepareAction(runtimeContext)
        }

        override fun start() {
            requireExternalOwnership(token)
            startAction()
        }

        override fun stopGracefully(): Mono<Void> {
            requireExternalOwnership(token)
            return Mono.defer(gracefulStopAction)
        }

        override fun forceStop() {
            requireExternalOwnership(token)
            forceStopAction()
        }
    }
}

/**
 * Centralizes the compatibility policy for directly-operated runtime components.
 */
private object StandaloneRuntimeFactory {
    private val SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(30)

    fun create(
        component: RuntimeComponent,
    ): WowRuntime =
        WowRuntime(
            components = listOf(component),
            shutdownTimeout = SHUTDOWN_TIMEOUT,
            shutdownQuietPeriod = Duration.ZERO,
        )
}
