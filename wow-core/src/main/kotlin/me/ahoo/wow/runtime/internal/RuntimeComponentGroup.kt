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

import me.ahoo.wow.infra.lifecycle.addSuppressedIfAbsent
import me.ahoo.wow.runtime.RuntimeComponent
import me.ahoo.wow.runtime.RuntimeContext
import me.ahoo.wow.runtime.RuntimeOwnershipClaim
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * One owner-bound runtime component and its transactional ownership claim.
 */
private class RuntimeComponentBinding(
    val original: RuntimeComponent,
    val component: RuntimeComponent,
    private val ownershipClaim: RuntimeOwnershipClaim,
) {
    fun commitOwnership() {
        ownershipClaim.commit()
    }

    override fun toString(): String = original.identityDescription()
}

/**
 * Ordered runtime component group.
 *
 * Ownership is claimed transactionally before any lifecycle work. Preparation
 * is a group-wide barrier: [start] only visits components that completed the
 * preparation pass. Cleanup always visits entered components in reverse order.
 */
internal class RuntimeComponentGroup private constructor(
    private val bindings: List<RuntimeComponentBinding>,
    private val reportFailure: (Throwable) -> Unit,
) {
    private val monitor = Any()
    private val slots = bindings.map { binding ->
        RuntimeComponentSlot(binding, reportFailure)
    }
    private val preparedSlots = mutableListOf<RuntimeComponentSlot>()
    private var forceStarted = false

    /**
     * Prepares every component without opening processing.
     *
     * Returns `false` when force-stop won the race before another component
     * could enter preparation.
     */
    fun prepare(
        runtimeContext: RuntimeContext,
        admissionGate: ((() -> Boolean) -> Boolean) = { admission -> admission() },
        afterEach: () -> Unit = {},
    ): Boolean {
        slots.forEach { slot ->
            var preparationAdmitted = false
            val admitted = admissionGate {
                preparationAdmitted = beginPreparation(slot)
                preparationAdmitted
            }
            if (!admitted) {
                return false
            }
            check(preparationAdmitted)
            invokeLifecycleAction(slot) {
                slot.binding.component.prepare(runtimeContext)
            }
            afterEach()
        }
        return true
    }

    /**
     * Opens processing only after the complete preparation pass.
     *
     * Returns `false` when force-stop won the race before another component
     * could enter start.
     */
    fun start(
        admissionGate: ((() -> Boolean) -> Boolean) = { admission -> admission() },
        afterEach: () -> Unit = {},
    ): Boolean {
        preparedSnapshot().forEach { slot ->
            if (!admissionGate { beginLifecycleAction(slot) }) {
                return false
            }
            invokeLifecycleAction(slot, slot.binding.component::start)
            afterEach()
        }
        return true
    }

    fun stopGracefully(
        shouldStop: () -> Boolean = { true },
    ): Mono<Void> =
        Mono.defer {
            val firstStopFailure = AtomicReference<Throwable?>()
            Flux.fromIterable(preparedSnapshot().asReversed())
                .concatMap { slot ->
                    Mono.defer {
                        if (shouldStop() && !forceStartedSnapshot()) {
                            slot.binding.component.stopGracefully()
                        } else {
                            Mono.empty()
                        }
                    }.onErrorResume { error ->
                        Exceptions.throwIfFatal(error)
                        reportFailure(error)
                        firstStopFailure.compareAndSet(null, error)
                        Mono.empty()
                    }
                }
                .then(
                    Mono.defer {
                        firstStopFailure.get()?.let { Mono.error(it) } ?: Mono.empty()
                    },
                )
        }

    fun forceStop(): Throwable? {
        val slots = synchronized(monitor) {
            if (forceStarted) {
                return null
            }
            forceStarted = true
            slots.toList()
        }
        val firstForceFailure = AtomicReference<Throwable?>()
        slots.asReversed().forEach { slot ->
            slot.forceStop()?.let { failure ->
                firstForceFailure.compareAndSet(null, failure)
            }
        }
        return firstForceFailure.get()
    }

    private fun beginPreparation(slot: RuntimeComponentSlot): Boolean =
        synchronized(monitor) {
            if (forceStarted) {
                false
            } else {
                check(slot.beginLifecycleAction())
                preparedSlots += slot
                true
            }
        }

    private fun beginLifecycleAction(slot: RuntimeComponentSlot): Boolean =
        synchronized(monitor) {
            !forceStarted && slot.beginLifecycleAction()
        }

    private fun preparedSnapshot(): List<RuntimeComponentSlot> =
        synchronized(monitor) {
            preparedSlots.toList()
        }

    private fun forceStartedSnapshot(): Boolean =
        synchronized(monitor) {
            forceStarted
        }

    @Suppress("TooGenericExceptionCaught")
    private fun invokeLifecycleAction(
        slot: RuntimeComponentSlot,
        action: () -> Unit,
    ) {
        val actionFailure = try {
            action()
            null
        } catch (error: Throwable) {
            Exceptions.throwIfFatal(error)
            reportFailure(error)
            error
        }
        val compensationFailure = slot.completeLifecycleAction()
        if (actionFailure != null) {
            throw actionFailure
        }
        if (compensationFailure != null) {
            throw compensationFailure
        }
    }

    companion object {
        @Suppress("TooGenericExceptionCaught")
        fun claim(
            components: List<RuntimeComponent>,
            reportFailure: (Throwable) -> Unit,
        ): RuntimeComponentGroup {
            requireDistinctIdentities(components)
            val pendingClaims = mutableListOf<RuntimeOwnershipClaim>()
            val bindings = mutableListOf<RuntimeComponentBinding>()
            val claimedComponentIdentities =
                Collections.newSetFromMap(IdentityHashMap<RuntimeComponent, Boolean>())
            try {
                components.forEach { component ->
                    val ownershipClaim = component.claimRuntimeOwnership()
                    pendingClaims += ownershipClaim
                    val ownerBoundComponent = ownershipClaim.component
                    require(claimedComponentIdentities.add(ownerBoundComponent)) {
                        "Runtime components resolve to the same owner-bound component" +
                            "[${ownerBoundComponent.identityDescription()}]."
                    }
                    bindings += RuntimeComponentBinding(
                        original = component,
                        component = ownerBoundComponent,
                        ownershipClaim = ownershipClaim,
                    )
                }
                bindings.forEach(RuntimeComponentBinding::commitOwnership)
            } catch (claimFailure: Throwable) {
                rollbackClaims(pendingClaims, claimFailure)
                throw claimFailure
            }
            return RuntimeComponentGroup(bindings, reportFailure)
        }

        @Suppress("TooGenericExceptionCaught")
        private fun rollbackClaims(
            pendingClaims: List<RuntimeOwnershipClaim>,
            claimFailure: Throwable,
        ) {
            pendingClaims.asReversed().forEach { pendingClaim ->
                try {
                    pendingClaim.rollback()
                } catch (rollbackFailure: Throwable) {
                    Exceptions.throwIfFatal(rollbackFailure)
                    claimFailure.addSuppressedIfAbsent(rollbackFailure)
                }
            }
        }

        private fun requireDistinctIdentities(components: List<RuntimeComponent>) {
            val identities =
                Collections.newSetFromMap(IdentityHashMap<RuntimeComponent, Boolean>())
            components.forEach { component ->
                require(identities.add(component)) {
                    "Runtime component[${component.identityDescription()}] is registered more than once."
                }
            }
        }
    }
}

private fun RuntimeComponent.identityDescription(): String =
    "${javaClass.name}@${System.identityHashCode(this).toString(16)}"

/**
 * Linearizes prepare/start actions with force-stop.
 *
 * When force-stop overlaps a lifecycle action, it first invokes force-stop to
 * unblock the action and invokes it once more after the action returns. This
 * compensates resources acquired after the first cancellation pass.
 */
private class RuntimeComponentSlot(
    val binding: RuntimeComponentBinding,
    private val reportFailure: (Throwable) -> Unit,
) {
    private val monitor = Any()
    private var lifecycleActionInFlight = false
    private var forceStarted = false
    private var forceCompleted = false
    private var forceOverlappedLifecycleAction = false
    private var compensationClaimed = false

    fun beginLifecycleAction(): Boolean =
        synchronized(monitor) {
            if (forceStarted) {
                false
            } else {
                check(!lifecycleActionInFlight) {
                    "Component lifecycle actions must not overlap: $binding."
                }
                lifecycleActionInFlight = true
                true
            }
        }

    fun completeLifecycleAction(): Throwable? {
        val compensate = synchronized(monitor) {
            check(lifecycleActionInFlight) {
                "No component lifecycle action is in flight: $binding."
            }
            lifecycleActionInFlight = false
            claimCompensationIfReady()
        }
        return if (compensate) invokeAndReportForceStop() else null
    }

    fun forceStop(): Throwable? {
        val shouldForce = synchronized(monitor) {
            if (forceStarted) {
                false
            } else {
                forceStarted = true
                forceOverlappedLifecycleAction = lifecycleActionInFlight
                true
            }
        }
        if (!shouldForce) {
            return null
        }
        val initialFailure = invokeAndReportForceStop()
        val compensate = synchronized(monitor) {
            forceCompleted = true
            claimCompensationIfReady()
        }
        val compensationFailure = if (compensate) invokeAndReportForceStop() else null
        return initialFailure ?: compensationFailure
    }

    private fun claimCompensationIfReady(): Boolean {
        if (!forceOverlappedLifecycleAction || !forceCompleted || lifecycleActionInFlight) {
            return false
        }
        if (compensationClaimed) {
            return false
        }
        compensationClaimed = true
        return true
    }

    @Suppress("TooGenericExceptionCaught")
    private fun invokeForceStop(): Throwable? =
        try {
            binding.component.forceStop()
            null
        } catch (error: Throwable) {
            Exceptions.throwIfFatal(error)
            error
        }

    private fun invokeAndReportForceStop(): Throwable? =
        invokeForceStop()?.also { failure ->
            reportFailure(failure)
        }
}
