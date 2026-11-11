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

package me.ahoo.wow.api.access

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller

/**
 * Who may read and who may change the aggregates whose state is [S]: for a caller, a predicate over their access
 * attributes ([AccessPredicate]) to [read] them and one to [write] them. A service declares one per aggregate whose
 * state declares access attributes (`@Access`), as a bean; [aggregate] names the aggregate when several share the
 * state type, null otherwise. Build one with [accessPolicy].
 *
 * A policy replaces the default rule ([ScopeAccess]) for its aggregate: the caller's owner and space are compared
 * only where the policy compares them, so a policy that keeps the aggregates to their owner and space says so
 * ([AccessScope.ownerAndSpace]).
 */
class AccessPolicy<S : Any> @InternalWowApi constructor(
    val stateType: Class<S>,
    val aggregate: AggregateType?,
    private val read: AccessScope<S>.(Caller) -> AccessPredicate,
    private val write: AccessScope<S>.(Caller) -> AccessPredicate,
) {
    private val scopes = Ownership.entries.associateWith { AccessScope<S>(it) }

    /** What [caller] may read of the aggregates, whose [ownership] is the aggregate's ([AccessScope.ownerAndSpace]). */
    fun read(caller: Caller, ownership: Ownership): AccessPredicate = scopes.getValue(ownership).read(caller)

    /** What [caller] may change of the aggregates, whose [ownership] is the aggregate's ([AccessScope.ownerAndSpace]). */
    fun write(caller: Caller, ownership: Ownership): AccessPredicate = scopes.getValue(ownership).write(caller)

    override fun toString(): String = "AccessPolicy(stateType=${stateType.name}, aggregate=$aggregate)"
}

/**
 * The access policy of the aggregates whose state is [S], from [block]: it gives [AccessPolicyBuilder.read] and
 * [AccessPolicyBuilder.write], or [AccessPolicyBuilder.readWrite] for one predicate of both. [aggregate] names the
 * aggregate when several share the state type.
 *
 * ```kotlin
 * @Bean
 * fun documentAccess(): AccessPolicy<DocumentState> = accessPolicy {
 *     read { caller ->
 *         anyOf(
 *             DocumentStateAccess.DEPARTMENT isIn caller.claims("departments"),
 *             DocumentStateAccess.SHARED_WITH contains caller.subject,
 *             DocumentStateAccess.ORG startsWith caller.claim("org"),
 *             allowIf(caller.hasRole("auditor")),
 *         )
 *     }
 *     write { caller -> DocumentStateAccess.DEPARTMENT isIn caller.claims("departments") }
 * }
 * ```
 */
inline fun <reified S : Any> accessPolicy(
    aggregate: AggregateType? = null,
    noinline block: AccessPolicyBuilder<S>.() -> Unit,
): AccessPolicy<S> = accessPolicy(S::class.java, aggregate, block)

/** The access policy of the aggregates whose state is [stateType], as the reified [accessPolicy] builds it. */
fun <S : Any> accessPolicy(
    stateType: Class<S>,
    aggregate: AggregateType? = null,
    block: AccessPolicyBuilder<S>.() -> Unit,
): AccessPolicy<S> = AccessPolicyBuilder(stateType, aggregate).apply(block).build()

/**
 * Builds an [AccessPolicy]. Nothing is granted by omission: a policy gives what may be read and what may be changed,
 * [read] and [write], or [readWrite] for both at once; one without a write predicate is refused, and
 * `write { denyAll() }` makes a read-only aggregate explicit.
 */
class AccessPolicyBuilder<S : Any> internal constructor(
    private val stateType: Class<S>,
    private val aggregate: AggregateType?,
) {
    private var read: (AccessScope<S>.(Caller) -> AccessPredicate)? = null
    private var write: (AccessScope<S>.(Caller) -> AccessPredicate)? = null

    /** What a caller may read of the aggregates. */
    fun read(predicate: AccessScope<S>.(Caller) -> AccessPredicate) {
        require(read == null) { "The access policy of ${stateType.name} gives two read predicates." }
        read = predicate
    }

    /** What a caller may change of the aggregates; `write { denyAll() }` for none. */
    fun write(predicate: AccessScope<S>.(Caller) -> AccessPredicate) {
        require(write == null) { "The access policy of ${stateType.name} gives two write predicates." }
        write = predicate
    }

    /** What a caller may read and change of the aggregates alike. */
    fun readWrite(predicate: AccessScope<S>.(Caller) -> AccessPredicate) {
        read(predicate)
        write(predicate)
    }

    internal fun build(): AccessPolicy<S> {
        val read = requireNotNull(read) {
            "The access policy of ${stateType.name} gives no read predicate: give read { … }, or readWrite { … } " +
                "for one predicate of reading and changing."
        }
        val write = requireNotNull(write) {
            "The access policy of ${stateType.name} gives no write predicate: nothing is granted by omission. Give " +
                "write { … }, readWrite { … } for one predicate of reading and changing, or write { denyAll() } " +
                "for an aggregate no caller changes."
        }
        return AccessPolicy(stateType, aggregate, read, write)
    }
}

/**
 * What an access policy's predicates are written with, for the aggregates whose state is [S]: comparisons of their
 * access attributes (`<State>Access`, typed by [S]) with the caller's claims, and logic. A comparison with a claim the
 * caller does not hold, a null or no values, denies ([AccessPredicate.Unknown]), as does one of a value the aggregate
 * does not have. A node builds it for the aggregate the policy governs, of that aggregate's [ownership].
 */
class AccessScope<S : Any> internal constructor(private val ownership: Ownership) {
    /** The attribute's value is [value]. */
    infix fun <V : Any> AccessAttribute.Single<S, V>.eq(value: V?): AccessPredicate =
        compare(this, AccessOperator.EQUAL, listOfNotNull(value))

    /** The attribute's value is one of [values]. */
    infix fun <V : Any> AccessAttribute.Single<S, V>.isIn(values: Collection<V>): AccessPredicate =
        compare(this, AccessOperator.IN, values.distinct())

    /** The attribute's value, a string, starts with [prefix]: a level of a materialized path, say. */
    infix fun AccessAttribute.Single<S, String>.startsWith(prefix: String?): AccessPredicate =
        compare(this, AccessOperator.STARTS_WITH, listOfNotNull(prefix))

    /** The aggregate has no value of the attribute. */
    fun AccessAttribute.Single<S, *>.isNull(): AccessPredicate =
        AccessPredicate.Comparison(this, AccessOperator.IS_NULL)

    /** The attribute's values hold [value]. */
    infix fun <V : Any> AccessAttribute.Multiple<S, V>.contains(value: V?): AccessPredicate =
        compare(this, AccessOperator.CONTAINS, listOfNotNull(value))

    /** The attribute's values hold one of [values]. */
    infix fun <V : Any> AccessAttribute.Multiple<S, V>.containsAny(values: Collection<V>): AccessPredicate =
        compare(this, AccessOperator.CONTAINS_ANY, values.distinct())

    /** The attribute holds no value. */
    fun AccessAttribute.Multiple<S, *>.isEmpty(): AccessPredicate =
        AccessPredicate.Comparison(this, AccessOperator.IS_EMPTY)

    /** Each of [predicates] holds; always when none is given. */
    fun allOf(vararg predicates: AccessPredicate): AccessPredicate = AccessPredicate.AllOf(predicates.toList())

    /** One of [predicates] holds; never when none is given. */
    fun anyOf(vararg predicates: AccessPredicate): AccessPredicate = AccessPredicate.AnyOf(predicates.toList())

    /** [predicate] does not hold, of a value the aggregate has: an absent value grants neither way. */
    fun not(predicate: AccessPredicate): AccessPredicate = AccessPredicate.Not(predicate)

    /** Every aggregate when [condition] holds, none otherwise: a condition on the caller alone, such as a role. */
    fun allowIf(condition: Boolean): AccessPredicate =
        if (condition) AccessPredicate.Allowed else AccessPredicate.Denied

    /** Every aggregate: public data, said explicitly. */
    fun allowAll(): AccessPredicate = AccessPredicate.Allowed

    /** No aggregate: `write { denyAll() }` for one no caller changes. */
    fun denyAll(): AccessPredicate = AccessPredicate.Denied

    /** The first value of the caller's claim [name], or null when the caller does not hold it. */
    fun Caller.claim(name: String): String? = attributes[name]?.firstOrNull()

    /** The values of the caller's claim [name], none when the caller does not hold it. */
    fun Caller.claims(name: String): List<String> = attributes[name].orEmpty()

    /** Whether the caller holds the role [role]: one of the values of its `roles` claim. */
    fun Caller.hasRole(role: String): Boolean = role in claims(ROLES)

    /**
     * The default rule of [caller] ([ScopeAccess]): the aggregates whose owner is the owner of the scope the caller acts
     * in and whose space is its space, each part compared only when the scope names it, as the aggregate's ownership
     * keeps them: an aggregate of its tenant ([Ownership.TENANT]) records no owner, so only the space is compared. A
     * policy replaces the default rule for its aggregate, so one that keeps the aggregates to their owner and space
     * combines its own attributes with this: `allOf(DocumentStateAccess.APP eq caller.app, ownerAndSpace(caller))`.
     */
    fun ownerAndSpace(caller: Caller): AccessPredicate = ScopeAccess.of(ownership.scopeOf(caller.scope))

    private fun compare(attribute: AccessAttribute<*, *>, operator: AccessOperator, values: List<Any>): AccessPredicate =
        if (values.isEmpty()) AccessPredicate.Unknown else AccessPredicate.Comparison(attribute, operator, values)

    private companion object {
        /** The claim of the caller's roles. */
        const val ROLES = "roles"
    }
}

/**
 * The aggregate each of [policies] governs, as a node resolves them once, for its reads and its commands alike: the
 * one it names, else the one of [aggregatesOf] its state type among the node's aggregates ([hosted]). It refuses,
 * naming the policy or the aggregate:
 *
 * - a policy whose state is no aggregate's of the node (when [strict], a node hosting every context of the
 *   service; otherwise such a policy is an aggregate's another node hosts, and is left out), or the state of
 *   several without naming one, or naming an aggregate whose state it is not;
 * - two policies of one aggregate;
 * - an aggregate of [declaring], one of the node that declares access attributes (with their names), without a
 *   policy: nothing is granted by omission.
 */
@InternalWowApi
fun resolveAccessPolicies(
    policies: List<AccessPolicy<*>>,
    hosted: Set<AggregateType>,
    aggregatesOf: (Class<*>) -> List<AggregateType>,
    declaring: Map<AggregateType, List<String>>,
    strict: Boolean = true,
): Map<AggregateType, AccessPolicy<*>> {
    val resolved = policies.mapNotNull { policy ->
        aggregateOf(policy, aggregatesOf(policy.stateType).filter(hosted::contains), strict)?.let { it to policy }
    }
    resolved.groupBy({ it.first }, { it.second }).forEach { (type, given) ->
        check(given.size == 1) { "The aggregate $type has ${given.size} access policies: $given." }
    }
    val byType = resolved.toMap()
    val ungoverned = declaring.filter { (type, names) -> names.isNotEmpty() && type !in byType }
    check(ungoverned.isEmpty()) {
        ungoverned.entries.joinToString("; ", postfix = ".") { (type, names) ->
            "The aggregate $type declares the access attributes $names, but no access policy is given for it: " +
                "declare an AccessPolicy bean of its state (accessPolicy { read { … }; write { … } }), since " +
                "nothing is granted by omission"
        }
    }
    return byType
}

/** The aggregate [policy] is of among [candidates], those of the node whose state is its state type. */
private fun aggregateOf(policy: AccessPolicy<*>, candidates: List<AggregateType>, strict: Boolean): AggregateType? {
    val named = policy.aggregate
    if (named != null) {
        check(named in candidates || !strict && candidates.isEmpty()) {
            "The access policy $policy names $named, which is not an aggregate of this node whose state is " +
                "${policy.stateType.name}: its aggregates of that state are $candidates."
        }
        return named.takeIf { it in candidates }
    }
    check(candidates.size <= 1) {
        "The access policy $policy is of the state of several aggregates, $candidates: name the one it " +
            "governs with accessPolicy(aggregate = …)."
    }
    check(candidates.isNotEmpty() || !strict) {
        "The access policy $policy is of no aggregate of this node: no aggregate's state is " +
            "${policy.stateType.name}."
    }
    return candidates.singleOrNull()
}
