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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.api.access.AccessPolicy
import me.ahoo.wow.api.access.AccessPredicate
import me.ahoo.wow.api.access.ScopeAccess
import me.ahoo.wow.api.access.resolveAccessPolicies
import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.log.EventStreamRecord

/**
 * The access policy ([AccessPolicy]) that decides who changes each aggregate type: the service's policy of it, the one
 * its reads are decided by too, or none, and the default rule ([ScopeAccess]) decides. The host resolves the
 * service's policies once, as the query side does; none by default.
 */
@InternalWowApi
fun interface WritePolicies {
    /** The access policy of [type], or null when the default rule decides. */
    fun of(type: AggregateType): AccessPolicy<*>?

    @InternalWowApi
    companion object {
        /** No policy: every aggregate is changed by the default rule. */
        @JvmField
        val NONE: WritePolicies = WritePolicies { null }

        /**
         * The service's [policies] of the aggregates of [models], the node's, resolved as its reads resolve them
         * ([resolveAccessPolicies]), with the same refusals: a model that declares access attributes without a policy
         * among them. [strict] when the node hosts every context of the service.
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            policies: List<AccessPolicy<*>>,
            models: Collection<AggregateModel<*>>,
            strict: Boolean = true,
        ): WritePolicies {
            val byState = models.groupBy({ it.stateType }, { it.type })
            val byType = resolveAccessPolicies(
                policies,
                models.mapTo(HashSet()) { it.type },
                { byState[it].orEmpty() },
                models.associate { it.type to it.accessNames.toList() },
                strict,
            )
            return if (byType.isEmpty()) NONE else WritePolicies { byType[it] }
        }
    }
}

/**
 * Whether the caller of [command] may change, or read, an aggregate of [model]: by the aggregate's access [policy] on
 * its access attributes, the built-in owner and space among them, or, without one, by the default rule
 * ([ScopeAccess]) on the command's scope, as the aggregate's queries are. Each predicate is built once, the first
 * time it is needed, and evaluated in memory ([AccessPredicate.allows]) on the state the kernel already holds.
 *
 * - A derived command ([isDerived]) is the server's own, and reaches every aggregate.
 * - Under a policy, so does a [service][Caller.service] acting as itself: a service is trusted, bounded by the
 *   commands its callers' configuration lets it send, as on its reads.
 * - Under a policy, a command without a caller (one a producer wrote on a command topic without one) reaches none:
 *   nothing is granted by omission.
 */
internal class CommandReach(
    private val model: AggregateModel<*>,
    private val policy: AccessPolicy<*>?,
    private val command: Command<*>,
) {
    /** Whether a policy of its own governs the aggregate. */
    val governed: Boolean get() = policy != null

    private val trusted = command.isDerived || policy != null && command.caller?.service == true

    private val write: AccessPredicate by lazy(LazyThreadSafetyMode.NONE) {
        predicate { policy, caller -> policy.write(caller, model.ownership) }
    }

    private val read: AccessPredicate by lazy(LazyThreadSafetyMode.NONE) {
        predicate { policy, caller -> policy.read(caller, model.ownership) }
    }

    /** Whether the caller may change [aggregate], as the kernel holds it. */
    fun writes(aggregate: CachedState): Boolean = trusted || write.allows { valueOf(it, aggregate) }

    /** Whether the caller may read [aggregate], as the kernel holds it. */
    fun reads(aggregate: CachedState): Boolean = trusted || read.allows { valueOf(it, aggregate) }

    /** Whether the caller may change an aggregate in [scope] whose declared attributes are [declared]. */
    fun writes(scope: Scope, declared: Map<String, Any?>): Boolean =
        trusted || write.allows { valueOf(it, scope, declared) }

    /**
     * Whether the caller of a retry may be answered [existing], which committed its request: by the default rule on
     * [before], the aggregate's scope before the stream; under a policy, if it may read the aggregate with [before]'s
     * owner and space and the declared attributes the stream records, its aggregate's current ones, which a stream not
     * yet stamped does not record. The owner and space are the ones the retried command was sent to, as by the default
     * rule, so a retry is answered after a later move of the aggregate as before it.
     */
    fun retries(existing: EventStreamRecord, before: Scope, codec: EventCodec): Boolean {
        if (trusted || policy == null) {
            return writes(before, emptyMap())
        }
        val access = existing.access ?: return false
        val declared = codec.declaredOf(model, AccessValues.declared(access))
        return read.allows { valueOf(it, before, declared) }
    }

    private fun predicate(of: (AccessPolicy<*>, Caller) -> AccessPredicate): AccessPredicate {
        val governing = policy ?: return ScopeAccess.of(command.scope)
        val caller = command.caller ?: return AccessPredicate.Denied
        return of(governing, caller)
    }

    private fun valueOf(attribute: AccessAttribute<*, *>, aggregate: CachedState): Any? =
        if (attribute.name in AccessAttribute.BUILT_IN) {
            ScopeAccess.valueOf(attribute, aggregate.scope)
        } else {
            declared(attribute, aggregate.declared(model))
        }

    private fun valueOf(attribute: AccessAttribute<*, *>, scope: Scope, declared: Map<String, Any?>): Any? =
        if (attribute.name in AccessAttribute.BUILT_IN) {
            ScopeAccess.valueOf(attribute, scope)
        } else {
            declared(attribute, declared)
        }

    /** The value of [attribute] among [declared], when the aggregate declares it as such; none otherwise. */
    private fun declared(attribute: AccessAttribute<*, *>, declared: Map<String, Any?>): Any? =
        declared[attribute.name]?.takeIf { attribute in model.accessAttributes }
}

/**
 * This command with its scope narrowed to what [model]'s ownership keeps: a tenant's aggregate neither
 * records nor checks an owner, only that part of the scope counts. An aggregate whose id is its owner's takes its id
 * for the owner of a command with none, or derived: a caller's other owner is kept, and refused ([admissible],
 * [creatable]).
 */
internal fun Command<*>.narrowedTo(model: AggregateModel<*>): Command<*> {
    val owned = model.ownsById && (scope.ownerId == null || isDerived)
    val narrowed = if (owned) scope.copy(ownerId = ref.id) else model.ownership.scopeOf(scope)
    return if (narrowed == scope) this else copyWith(scope = narrowed)
}

/** Whether the model's aggregate's id is its owner's. */
internal val AggregateModel<*>.ownsById: Boolean get() = idSource == AggregateIdSource.OWNER

/**
 * The scope of the aggregate [command] creates: the command's, but for an aggregate whose id is its owner's, whose
 * owner is its id whoever creates it ([creatable] refuses a caller of another owner).
 */
internal fun createdIn(model: AggregateModel<*>, command: Command<*>): Scope =
    if (model.ownsById) command.scope.copy(ownerId = command.ref.id) else command.scope

/**
 * Whether this command was derived by the server's own code rather than sent by a caller: a saga's,
 * an `@OnRejected` compensation, a timer's, or one another context sent through its command topic. Such a command
 * belongs to a chain, so it carries a [Command.causation]; admission gives a caller's none (it is its own root).
 */
@InternalWowApi
val Command<*>.isDerived: Boolean get() = causation != null
