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
import me.ahoo.wow.runtime.RuntimeOwnership
import reactor.core.publisher.Mono
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/**
 * Internal transactional ownership claim.
 */
internal interface RuntimeOwnershipClaim {
    val component: RuntimeComponent

    fun commit()

    fun rollback()
}

/**
 * Transactional ownership state for a lifecycle that has exactly one driver.
 *
 * A pending external claim can be rolled back without touching lifecycle
 * resources. Once committed, or once the public lifecycle is used directly,
 * ownership remains permanent.
 */
internal class TransactionalExclusiveRuntimeOwnership(
    private val ownerDescription: String,
) {
    private sealed interface Ownership {
        data object Unclaimed : Ownership

        data class Direct(val token: Any) : Ownership

        data class External(
            val token: Any,
            var committed: Boolean = false,
        ) : Ownership
    }

    private val monitor = Any()
    private var ownership: Ownership = Ownership.Unclaimed

    fun claim(): Claim {
        val token = Any()
        synchronized(monitor) {
            check(ownership === Ownership.Unclaimed) {
                "$ownerDescription runtime ownership is already ${ownership.description()}."
            }
            ownership = Ownership.External(token)
        }
        return Claim(token)
    }

    fun claimDirectAccess(token: Any) {
        synchronized(monitor) {
            when (val current = ownership) {
                Ownership.Unclaimed -> ownership = Ownership.Direct(token)
                is Ownership.Direct -> check(current.token === token) {
                    "$ownerDescription lifecycle is already owned by another direct adapter."
                }
                is Ownership.External -> error(
                    "$ownerDescription lifecycle is owned by an external WowRuntime.",
                )
            }
        }
    }

    private fun requireActive(token: Any) {
        synchronized(monitor) {
            val current = ownership as? Ownership.External
            check(current?.token === token && current.committed) {
                "$ownerDescription runtime ownership is no longer active."
            }
        }
    }

    private fun commit(token: Any) {
        synchronized(monitor) {
            val current = ownership
            if (current is Ownership.External && current.token === token) {
                current.committed = true
            }
        }
    }

    private fun rollback(token: Any) {
        synchronized(monitor) {
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
            is Ownership.Direct -> "DIRECT"
            is Ownership.External -> "EXTERNAL"
        }

    inner class Claim internal constructor(
        private val token: Any,
    ) {
        fun requireActive() {
            this@TransactionalExclusiveRuntimeOwnership.requireActive(token)
        }

        fun commit() {
            this@TransactionalExclusiveRuntimeOwnership.commit(token)
        }

        fun rollback() {
            this@TransactionalExclusiveRuntimeOwnership.rollback(token)
        }
    }
}

/**
 * Shares exclusive ownership state between every claim of the same component
 * or adapted lifecycle identity.
 *
 * Keys are weak and compared by identity so registration does not extend the
 * owner's lifetime and custom `equals` implementations cannot merge ownership.
 */
internal object RuntimeExclusiveOwnershipRegistry {
    private val monitor = Any()
    private val staleOwners = ReferenceQueue<Any>()
    private val ownershipByOwner =
        mutableMapOf<IdentityWeakReference, TransactionalExclusiveRuntimeOwnership>()

    fun ownershipFor(
        owner: Any,
        ownerKind: String,
    ): TransactionalExclusiveRuntimeOwnership =
        synchronized(monitor) {
            removeStaleOwners()
            val lookup = IdentityWeakReference(owner)
            ownershipByOwner[lookup]
                ?: TransactionalExclusiveRuntimeOwnership(
                    ownerDescription = buildOwnerDescription(ownerKind, owner),
                ).also { ownership ->
                    ownershipByOwner[IdentityWeakReference(owner, staleOwners)] = ownership
                }
        }

    private fun removeStaleOwners() {
        while (true) {
            val stale = staleOwners.poll() as? IdentityWeakReference ?: return
            ownershipByOwner.remove(stale)
        }
    }
}

/**
 * Component-local default ownership state. Keeping this state in the stable
 * public handle avoids a process-wide registry for normal runtime components.
 */
internal class StableExclusiveRuntimeOwnership {
    private val monitor = Any()
    private var owner: RuntimeComponent? = null
    private var ownership: TransactionalExclusiveRuntimeOwnership? = null

    fun claim(component: RuntimeComponent): RuntimeOwnershipClaim {
        val componentOwnership = synchronized(monitor) {
            val currentOwner = owner
            check(currentOwner == null || currentOwner === component) {
                "RuntimeOwnership must be retained by exactly one RuntimeComponent."
            }
            if (currentOwner == null) {
                owner = component
            }
            ownership
                ?: TransactionalExclusiveRuntimeOwnership(
                    ownerDescription = buildOwnerDescription(
                        ownerKind = "RuntimeComponent",
                        owner = component,
                    ),
                ).also { ownership = it }
        }
        return claimExclusiveRuntimeOwnership(component, componentOwnership)
    }
}

internal fun claimExclusiveRuntimeOwnership(
    component: RuntimeComponent,
    ownership: TransactionalExclusiveRuntimeOwnership,
): RuntimeOwnershipClaim {
    val claim = ownership.claim()
    return object : RuntimeOwnershipClaim {
        override val component: RuntimeComponent =
            ExclusiveOwnerBoundRuntimeComponent(component, claim)

        override fun commit() {
            claim.commit()
        }

        override fun rollback() {
            claim.rollback()
        }
    }
}

private class ExclusiveOwnerBoundRuntimeComponent(
    private val delegate: RuntimeComponent,
    private val claim: TransactionalExclusiveRuntimeOwnership.Claim,
) : RuntimeComponent {
    override val runtimeOwnership: RuntimeOwnership =
        RuntimeOwnership.unclaimable()

    override fun prepare(runtimeContext: RuntimeContext) {
        claim.requireActive()
        delegate.prepare(runtimeContext)
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
        delegate.forceStop()
    }
}

private class IdentityWeakReference : WeakReference<Any> {
    private val identityHash: Int

    constructor(referent: Any) : super(referent) {
        identityHash = System.identityHashCode(referent)
    }

    constructor(
        referent: Any,
        referenceQueue: ReferenceQueue<Any>,
    ) : super(referent, referenceQueue) {
        identityHash = System.identityHashCode(referent)
    }

    override fun hashCode(): Int = identityHash

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is IdentityWeakReference) {
            return false
        }
        val referent = get() ?: return false
        return referent === other.get()
    }
}

private fun buildOwnerDescription(
    ownerKind: String,
    owner: Any,
): String =
    "$ownerKind[${owner.javaClass.name}@${System.identityHashCode(owner).toString(16)}]"
