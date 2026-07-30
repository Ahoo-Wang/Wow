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

import me.ahoo.wow.runtime.RuntimeComponent
import me.ahoo.wow.runtime.RuntimeContext
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Ordered runtime component group.
 *
 * Preparation is a group-wide barrier: [start] only visits components that
 * completed preparation. Cleanup visits entered components in reverse order.
 */
internal class RuntimeComponentGroup(
    components: List<RuntimeComponent>,
    private val reportFailure: (Throwable) -> Unit,
) {
    private val monitor = Any()
    private val slots = components
        .also(::requireDistinctIdentities)
        .map { component -> RuntimeComponentSlot(component, reportFailure) }
    private val preparedSlots = mutableListOf<RuntimeComponentSlot>()
    private var forceStarted = false

    /**
     * Prepares every component without opening processing.
     *
     * Returns `false` when force-stop wins before another component can enter.
     */
    fun prepare(
        runtimeContext: RuntimeContext,
        admissionGate: ((() -> Boolean) -> Boolean) = { admission -> admission() },
        afterEach: () -> Unit = {},
    ): Mono<Boolean> =
        Flux.fromIterable(slots)
            .concatMap { slot ->
                Mono.defer {
                    if (!admissionGate { beginPreparation(slot) }) {
                        return@defer Mono.just(false)
                    }
                    invokeLifecyclePublisher(slot) {
                        slot.component.prepare(runtimeContext)
                    }
                        .doOnSuccess {
                            afterEach()
                        }
                        .thenReturn(true)
                }
            }
            .takeUntil { prepared -> !prepared }
            .last(true)

    /**
     * Closes component intake in registration order after global admission closes.
     */
    fun quiesce(
        shouldQuiesce: () -> Boolean = { true },
    ): Boolean {
        preparedSnapshot().forEach { slot ->
            if (!shouldQuiesce() || !beginLifecycleAction(slot)) {
                return false
            }
            invokeLifecycleAction(slot, slot.component::quiesce)
        }
        return true
    }

    /**
     * Opens processing only after the complete preparation pass.
     */
    fun start(
        admissionGate: ((() -> Boolean) -> Boolean) = { admission -> admission() },
        afterEach: () -> Unit = {},
    ): Boolean {
        preparedSnapshot().forEach { slot ->
            if (!admissionGate { beginLifecycleAction(slot) }) {
                return false
            }
            invokeLifecycleAction(slot, slot.component::start)
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
                    stopGracefully(slot, shouldStop).onErrorResume { error ->
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

    private fun stopGracefully(
        slot: RuntimeComponentSlot,
        shouldStop: () -> Boolean,
    ): Mono<Void> =
        Mono.defer {
            val compensationFailure = AtomicReference<Throwable?>()
            Mono.using(
                { shouldStop() && beginLifecycleAction(slot) },
                { lifecycleEntered ->
                    if (lifecycleEntered) {
                        gracefulStopPublisher(slot)
                    } else {
                        Mono.empty()
                    }
                },
                { lifecycleEntered ->
                    if (lifecycleEntered) {
                        compensationFailure.set(slot.completeLifecycleAction())
                    }
                },
                true,
            )
                .then(
                    Mono.defer {
                        compensationFailure.get()?.let { Mono.error(it) } ?: Mono.empty()
                    },
                )
        }

    @Suppress("TooGenericExceptionCaught")
    private fun gracefulStopPublisher(
        slot: RuntimeComponentSlot,
    ): Mono<Void> {
        val publisher = try {
            slot.component.stopGracefully()
        } catch (error: Throwable) {
            Exceptions.throwIfFatal(error)
            reportFailure(error)
            return Mono.error(error)
        }
        if (slot.isForceStarted) {
            return Mono.empty()
        }
        return publisher.doOnError { error ->
            Exceptions.throwIfFatal(error)
            reportFailure(error)
        }
    }

    fun forceStop(): Throwable? {
        val slots = synchronized(monitor) {
            if (forceStarted) {
                return null
            }
            forceStarted = true
            slots.toList()
        }
        var firstForceFailure: Throwable? = null
        slots.asReversed().forEach { slot ->
            slot.forceStop()?.let { failure ->
                if (firstForceFailure == null) {
                    firstForceFailure = failure
                }
            }
        }
        return firstForceFailure
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

    @Suppress("TooGenericExceptionCaught")
    private fun invokeLifecyclePublisher(
        slot: RuntimeComponentSlot,
        action: () -> Mono<Void>,
    ): Mono<Void> =
        Mono.defer {
            val compensationFailure = AtomicReference<Throwable?>()
            Mono.using(
                { true },
                {
                    val publisher = try {
                        action()
                    } catch (error: Throwable) {
                        Exceptions.throwIfFatal(error)
                        reportFailure(error)
                        return@using Mono.error(error)
                    }
                    if (slot.isForceStarted) {
                        return@using Mono.empty()
                    }
                    publisher.doOnError { error ->
                        Exceptions.throwIfFatal(error)
                        reportFailure(error)
                    }
                },
                {
                    compensationFailure.set(slot.completeLifecycleAction())
                },
                true,
            )
                .then(
                    Mono.defer {
                        compensationFailure.get()?.let { Mono.error(it) } ?: Mono.empty()
                    },
                )
        }

    private companion object {
        fun requireDistinctIdentities(components: List<RuntimeComponent>) {
            val identities =
                Collections.newSetFromMap(IdentityHashMap<RuntimeComponent, Boolean>())
            components.forEach { component ->
                require(identities.add(component)) {
                    "Runtime component[${component.identityDescription()}] is registered more than once."
                }
            }
        }

        fun RuntimeComponent.identityDescription(): String =
            "${javaClass.name}@${System.identityHashCode(this).toString(16)}"
    }
}

/**
 * Linearizes lifecycle method entry with force-stop.
 *
 * When force-stop overlaps a lifecycle action, it invokes force-stop once to
 * unblock that action and once more after the action returns or its graceful
 * publisher terminates or is cancelled. The second pass compensates resources
 * acquired after the first cancellation pass.
 */
private class RuntimeComponentSlot(
    val component: RuntimeComponent,
    private val reportFailure: (Throwable) -> Unit,
) {
    private val monitor = Any()
    private var lifecycleActionInFlight = false
    private var forceStarted = false
    private var forceCompleted = false
    private var forceOverlappedLifecycleAction = false
    private var compensationClaimed = false

    val isForceStarted: Boolean
        get() = synchronized(monitor) {
            forceStarted
        }

    fun beginLifecycleAction(): Boolean =
        synchronized(monitor) {
            if (forceStarted) {
                false
            } else {
                check(!lifecycleActionInFlight) {
                    "Component lifecycle actions must not overlap: ${component.identityDescription()}."
                }
                lifecycleActionInFlight = true
                true
            }
        }

    fun completeLifecycleAction(): Throwable? {
        val compensate = synchronized(monitor) {
            check(lifecycleActionInFlight) {
                "No component lifecycle action is in flight: ${component.identityDescription()}."
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
            component.forceStop()
            null
        } catch (error: Throwable) {
            Exceptions.throwIfFatal(error)
            error
        }

    private fun invokeAndReportForceStop(): Throwable? =
        invokeForceStop()?.also(reportFailure)

    private fun RuntimeComponent.identityDescription(): String =
        "${javaClass.name}@${System.identityHashCode(this).toString(16)}"
}
