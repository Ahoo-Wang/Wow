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

package me.ahoo.wow.test

import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.api.access.AccessPolicy
import me.ahoo.wow.api.access.AccessPredicate
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.kernel.AggregateModel

/**
 * Checks the access [policy] of [model]'s aggregates: given an aggregate (its events, or its state), whether a caller
 * may read it and change it, as the kernel and the queries decide (the access attributes the state derives, the
 * built-in owner and space its scope's).
 *
 * ```kotlin
 * accessPolicySpec(documentModel, documentAccess) {
 *     given(DocumentCreated("sales"), DocumentShared("bob"))
 *     whenCaller(subject = "bob", claims = mapOf("departments" to listOf("hr"))) {
 *         expectRead()
 *         expectNoWrite()
 *     }
 * }
 * ```
 *
 * A check fails, too, when the policy compares an attribute [model] does not declare, which a node does not refuse at
 * its start. A policy's references are typed by its state (`AccessAttribute<in S, V>`, generated in `<State>Access`),
 * so such a comparison hardly compiles; one that does, as in a policy built against another version of the state,
 * matches no aggregate when a node evaluates it (it fails closed), and the node logs it once.
 */
fun <S : Any> accessPolicySpec(
    model: AggregateModel<S>,
    policy: AccessPolicy<S>,
    block: AccessPolicySpec<S>.() -> Unit
) {
    require(policy.stateType == model.stateType) {
        "$policy is not a policy of ${model.type}'s state, ${model.stateType.name}."
    }
    AccessPolicySpec(model, policy).block()
}

/** The aggregate an access policy is checked on, and the callers it is checked for ([accessPolicySpec]). */
@WowTestDsl
class AccessPolicySpec<S : Any> internal constructor(
    private val model: AggregateModel<S>,
    private val policy: AccessPolicy<S>,
) {
    private var state: S = model.initialState(model.type.ref(DEFAULT_TENANT, ID))
    private var scope: Scope = Scope.NONE

    /** The aggregate after [events], one commit each, in [scope]. */
    fun given(vararg events: Any, scope: Scope = Scope.NONE) {
        val aggregate = GivenAggregate(model, model.type.ref(DEFAULT_TENANT, ID), events.toList(), scope)
        state = aggregate.state
        this.scope = scope
    }

    /** The aggregate in [state] and [scope]. */
    fun givenState(state: S, scope: Scope = Scope.NONE) {
        this.state = state
        this.scope = scope
    }

    /** Checks what [caller] may do with the aggregate. */
    fun whenCaller(caller: Caller, block: AccessExpectations.() -> Unit) {
        AccessExpectations { write, granted -> check(caller, write, granted) }.block()
    }

    /** Checks what a caller [subject] may do, holding [claims], with [roles] (its `roles` claim). */
    fun whenCaller(
        subject: String? = null,
        claims: Map<String, List<String>> = emptyMap(),
        roles: List<String> = emptyList(),
        block: AccessExpectations.() -> Unit,
    ) {
        val attributes = if (roles.isEmpty()) claims else claims + (ROLES to roles)
        whenCaller(Caller(subject = subject, attributes = attributes), block)
    }

    /** Whether [caller] may change ([write]) or read the aggregate is [granted]; fails, saying why, otherwise. */
    private fun check(caller: Caller, write: Boolean, granted: Boolean) {
        val predicate = if (write) policy.write(caller, model.ownership) else policy.read(caller, model.ownership)
        val action = if (write) "change" else "read"
        val values = model.accessOf(state)
        val undeclared = attributesOf(predicate).filter { it.name !in AccessAttribute.BUILT_IN }
            .filterNot { it in model.accessAttributes }
        if (undeclared.isNotEmpty()) {
            throw AssertionError(
                "The access policy compares ${undeclared.map { it.name }}, which ${model.type} does not declare.",
            )
        }
        val allows = predicate.allows { attribute ->
            when (attribute) {
                AccessAttribute.OWNER -> scope.ownerId
                AccessAttribute.SPACE -> scope.spaceId
                else -> values[attribute.name]
            }
        }
        if (allows != granted) {
            throw AssertionError(
                "Expected the caller ${if (granted) "to" else "not to"} $action the aggregate, but it " +
                    "${if (allows) "may" else "may not"}: $predicate on $values in $scope.",
            )
        }
    }

    private companion object {
        const val ID = "access-1"
        const val ROLES = "roles"

        fun attributesOf(predicate: AccessPredicate): List<AccessAttribute<*, *>> = when (predicate) {
            is AccessPredicate.Comparison -> listOf(predicate.attribute)
            is AccessPredicate.Not -> attributesOf(predicate.predicate)
            is AccessPredicate.AllOf -> predicate.predicates.flatMap(::attributesOf)
            is AccessPredicate.AnyOf -> predicate.predicates.flatMap(::attributesOf)
            else -> emptyList()
        }
    }
}

/** What one caller may do with the aggregate of an [AccessPolicySpec]. */
@WowTestDsl
class AccessExpectations internal constructor(private val check: (write: Boolean, granted: Boolean) -> Unit) {
    /** The caller may read the aggregate. */
    fun expectRead() = check(false, true)

    /** The caller may not read the aggregate. */
    fun expectNoRead() = check(false, false)

    /** The caller may change the aggregate. */
    fun expectWrite() = check(true, true)

    /** The caller may not change the aggregate. */
    fun expectNoWrite() = check(true, false)
}
