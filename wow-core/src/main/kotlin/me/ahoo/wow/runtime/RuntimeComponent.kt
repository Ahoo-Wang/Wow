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

import me.ahoo.wow.infra.lifecycle.ForceStoppable
import me.ahoo.wow.infra.lifecycle.Lifecycle
import me.ahoo.wow.runtime.internal.RuntimeExclusiveOwnershipRegistry
import me.ahoo.wow.runtime.internal.TransactionalExclusiveRuntimeOwnership
import me.ahoo.wow.runtime.internal.claimExclusiveRuntimeOwnership
import reactor.core.publisher.Mono

/**
 * Capability for components that participate in the runtime readiness barrier.
 */
fun interface RuntimePreparable {
    /**
     * Prepares this component without opening message processing.
     *
     * Components should register intake closure through [RuntimeContext.onClose],
     * track complete asynchronous work with [RuntimeContext.tryAcquire], and report
     * terminal pipeline errors with [RuntimeContext.reportFailure].
     */
    fun prepare(runtimeContext: RuntimeContext)
}

/**
 * Complete lifecycle contract required for components managed by [WowRuntime].
 *
 * Construction and ownership claims must be inert: acquire resources only from
 * [RuntimePreparable.prepare] or [Lifecycle.start]. [ForceStoppable.forceStop]
 * must nevertheless be safe before preparation so a failed container refresh
 * can release any accidentally pre-existing resources.
 *
 * [ForceStoppable.forceStop] may be invoked again when force-stop overlaps
 * `prepare` or `start`, so the second pass can compensate resources acquired
 * after the first cancellation pass.
 */
interface RuntimeComponent :
    Lifecycle,
    RuntimePreparable,
    ForceStoppable {
    /**
     * Claims this component for one external [WowRuntime] and returns the
     * owner-bound component view that only that owner may invoke.
     *
     * The default implementation claims this component instance exclusively
     * and supplies the runtime with an owner-bound view. A genuinely reentrant,
     * concurrency-safe component may explicitly return
     * [RuntimeOwnershipClaim.shared]. The claim must be prompt and must not
     * acquire external resources.
     */
    fun claimRuntimeOwnership(): RuntimeOwnershipClaim =
        RuntimeOwnershipClaim.exclusive(this)
}

/**
 * Transactional runtime ownership claim.
 *
 * [commit] makes a successful multi-component claim permanent.
 * [rollback] restores the unclaimed state if a later component
 * claim fails. Neither operation may acquire resources, invoke lifecycle work,
 * block, or throw.
 */
interface RuntimeOwnershipClaim {
    /**
     * The component view that the claiming runtime must invoke. Resolution must
     * be prompt, stable, and non-throwing.
     */
    val component: RuntimeComponent

    /**
     * Makes this ownership permanent. Must be prompt, idempotent, and non-throwing.
     */
    fun commit()

    /**
     * Restores the unclaimed state before commit. Must be prompt, idempotent,
     * and non-throwing.
     */
    fun rollback()

    companion object {
        /**
         * Creates a transactional, identity-based exclusive ownership claim.
         */
        fun exclusive(component: RuntimeComponent): RuntimeOwnershipClaim =
            claimExclusiveRuntimeOwnership(component)

        /**
         * Creates an explicit shared ownership claim.
         *
         * Use only when lifecycle calls from multiple runtimes are genuinely
         * safe and do not share mutable or externally owned resources.
         */
        fun shared(component: RuntimeComponent): RuntimeOwnershipClaim =
            SharedRuntimeOwnershipClaim(component)
    }
}

private class SharedRuntimeOwnershipClaim(
    override val component: RuntimeComponent,
) : RuntimeOwnershipClaim {
    override fun commit() = Unit

    override fun rollback() = Unit
}

/**
 * Explicitly adapts a legacy [Lifecycle] to the stronger runtime contract.
 *
 * [forceStopAction] must provide real, prompt cancellation. Subscribing to
 * [Lifecycle.stopGracefully] is not a valid force-stop implementation.
 */
class RuntimeLifecycleAdapter(
    private val delegate: Lifecycle,
    private val forceStopAction: () -> Unit,
    private val prepareAction: (RuntimeContext) -> Unit = {},
) : RuntimeComponent {
    init {
        require(delegate !is RuntimeComponent) {
            "RuntimeLifecycleAdapter cannot wrap RuntimeComponent[$delegate] because " +
                "that would bypass its runtime ownership."
        }
    }

    private val directAccessToken = Any()
    private val ownership = RuntimeExclusiveOwnershipRegistry.ownershipFor(
        owner = delegate,
        ownerKind = "RuntimeLifecycleAdapter",
    )

    override fun claimRuntimeOwnership(): RuntimeOwnershipClaim {
        val claim = ownership.claim()
        return object : RuntimeOwnershipClaim {
            override val component: RuntimeComponent = OwnershipComponent(claim)

            override fun commit() {
                claim.commit()
            }

            override fun rollback() {
                claim.rollback()
            }
        }
    }

    override fun prepare(runtimeContext: RuntimeContext) {
        ownership.claimDirectAccess(directAccessToken)
        prepareAction(runtimeContext)
    }

    override fun start() {
        ownership.claimDirectAccess(directAccessToken)
        delegate.start()
    }

    override fun stopGracefully(): Mono<Void> {
        ownership.claimDirectAccess(directAccessToken)
        return delegate.stopGracefully()
    }

    override fun forceStop() {
        ownership.claimDirectAccess(directAccessToken)
        forceStopAction()
    }

    private inner class OwnershipComponent(
        private val claim: TransactionalExclusiveRuntimeOwnership.Claim,
    ) : RuntimeComponent {
        override fun claimRuntimeOwnership(): RuntimeOwnershipClaim =
            error("An owner-bound runtime component cannot be claimed again.")

        override fun prepare(runtimeContext: RuntimeContext) {
            claim.requireActive()
            prepareAction(runtimeContext)
        }

        override fun start() {
            claim.requireActive()
            delegate.start()
        }

        override fun stopGracefully(): Mono<Void> {
            claim.requireActive()
            return delegate.stopGracefully()
        }

        override fun forceStop() {
            claim.requireActive()
            forceStopAction()
        }
    }
}
