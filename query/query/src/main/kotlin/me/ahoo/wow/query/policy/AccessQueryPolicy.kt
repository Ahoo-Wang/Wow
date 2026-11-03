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

package me.ahoo.wow.query.policy

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.api.access.AccessPolicy
import me.ahoo.wow.api.access.AccessPredicate
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.manifest.AccessAttributeManifest
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.Ownership
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.dataset.DatasetQueryModelProfile
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelProfile
import me.ahoo.wow.query.spec.SystemField
import me.ahoo.wow.spi.json.query.SnapshotFields
import java.util.concurrent.ConcurrentHashMap

/**
 * The access policies of a node's aggregates ([AccessPolicy], a bean of the service for each aggregate), each resolved
 * to its aggregate and checked against the Manifest once, when the engine is built ([of]). An aggregate with a policy
 * of its own is [governed][governs]: its reads are what the policy's `read` lets a caller read ([AccessQueryPolicy]).
 * Any other reads by the default rule, its caller's owner and space ([ScopeQueryPolicy]), as before access policies.
 */
@InternalWowApi
class AccessPolicies private constructor(
    private val policies: Map<AggregateType, AccessPolicy<*>>,
    private val aggregates: Map<AggregateType, AggregateManifest>,
) {
    /** The aggregates with a policy of their own. */
    val governed: Set<AggregateType>
        get() = policies.keys

    /** The policy of [type], or null when it reads by the default rule. */
    fun of(type: AggregateType): AccessPolicy<*>? = policies[type]

    /** Whether [type] reads by a policy of its own rather than by the default rule. */
    fun governs(type: AggregateType): Boolean = type in policies

    /** Whether [dataset]'s rows are those of an aggregate that reads by a policy of its own, which its scope binds. */
    fun governs(dataset: QueryDataset): Boolean = dataset.scope.access?.let { governs(it.aggregate) } == true

    /** Whether the node has [type]: one of the Manifest's aggregates. */
    fun has(type: AggregateType): Boolean = type in aggregates

    /**
     * Whether [attribute] is one [type] has, as a policy of it may compare it: [AccessAttribute.SPACE] of every
     * aggregate, [AccessAttribute.OWNER] of one its owner holds, and a declared one by its name, type and multiplicity.
     */
    fun declares(type: AggregateType, attribute: AccessAttribute<*, *>): Boolean {
        val aggregate = aggregates[type] ?: return false
        return when (attribute) {
            AccessAttribute.OWNER -> aggregate.ownership == Ownership.OWNER
            AccessAttribute.SPACE -> true
            else -> aggregate.access.any { it.name == attribute.name && it.matches(attribute) }
        }
    }

    @InternalWowApi
    companion object {
        /** No policy: every aggregate reads by the default rule. */
        @JvmField
        val NONE: AccessPolicies = AccessPolicies(emptyMap(), emptyMap())

        /**
         * The access policies [policies] of the aggregates of [manifest] in [contexts], each resolved to the aggregate
         * [aggregatesOf] gives for its state type (the types whose state is of that class), or the one it names. It
         * refuses, naming the policy or the aggregate:
         *
         * - a policy whose state is no aggregate's of the node (when [strict], a node hosting every context of the
         *   service; otherwise such a policy is an aggregate's another node hosts, and is left out), or the state of
         *   several without naming one, or naming an aggregate whose state it is not;
         * - two policies of one aggregate;
         * - an aggregate that declares access attributes without a policy: nothing is granted by omission.
         *
         * The attributes a policy compares are checked as it is evaluated ([declares]): its references are typed by its
         * state, so only a policy built against another version of the state names one its aggregate does not declare.
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            manifest: Manifest,
            contexts: Set<String>,
            policies: List<AccessPolicy<*>>,
            aggregatesOf: (Class<*>) -> List<AggregateType>,
            strict: Boolean = true,
        ): AccessPolicies {
            val aggregates = manifest.contexts.filter { it.name in contexts }.flatMap { context ->
                context.aggregates.map { AggregateType(context.name, it.name) to it }
            }.toMap()
            val resolved = policies.mapNotNull { policy ->
                aggregateOf(policy, aggregatesOf(policy.stateType).filter(aggregates::containsKey), strict)
                    ?.let { it to policy }
            }
            resolved.groupBy({ it.first }, { it.second }).forEach { (type, given) ->
                check(given.size == 1) { "The aggregate $type has ${given.size} access policies: $given." }
            }
            val byType = resolved.toMap()
            val ungoverned = aggregates.filter { (type, aggregate) -> aggregate.access.isNotEmpty() && type !in byType }
            check(ungoverned.isEmpty()) {
                ungoverned.entries.joinToString("; ", postfix = ".") { (type, aggregate) ->
                    "The aggregate $type declares the access attributes ${aggregate.access.map { it.name }}, but " +
                        "no access policy is given for it: declare an AccessPolicy bean of its state " +
                        "(accessPolicy { read { … }; write { … } }), since nothing is granted by omission"
                }
            }
            return AccessPolicies(byType, aggregates)
        }

        /** The aggregate [policy] is of among [candidates], those of the node whose state is its state type. */
        private fun aggregateOf(
            policy: AccessPolicy<*>,
            candidates: List<AggregateType>,
            strict: Boolean,
        ): AggregateType? {
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

        private fun AccessAttributeManifest.matches(attribute: AccessAttribute<*, *>): Boolean =
            multiple == attribute.multiple && type == typeOf(attribute.type)

        /** The Manifest's type of an attribute whose values are of [type]: an enum is written as a string. */
        private fun typeOf(type: Class<*>): AccessAttributeManifest.Type = when (type) {
            Int::class.javaObjectType, Long::class.javaObjectType -> AccessAttributeManifest.Type.INTEGER
            Boolean::class.javaObjectType -> AccessAttributeManifest.Type.BOOLEAN
            else -> AccessAttributeManifest.Type.STRING
        }
    }
}

/**
 * The `read` predicate of each [governed][AccessPolicies.governs] aggregate as a [QueryPolicy]: what a caller may read
 * of it, compiled to a filter ([AccessFilters]) and appended at admission step 2, right after the scope. An aggregate
 * it does not govern is left to the default rule ([ScopeQueryPolicy]).
 *
 * - **Who it reads for.** A caller as it is ([QueryCaller.caller]): its subject, scope and claims are what the
 *   predicate compares. Trusted in-process code (no caller) and a [service][Caller.service] reading as itself are read
 *   by no access policy: a service is trusted, bounded by the models `wow.callers.<service>.reads` lets it read. The
 *   tenant still bounds them.
 * - **Snapshots.** Each declared attribute is compared on `access.<name>`, [AccessAttribute.OWNER] and
 *   [AccessAttribute.SPACE] on the snapshot's owner and space, and a snapshot whose access attributes are not recorded
 *   (`access` absent) is never read.
 * - **State reads.** The same filter, evaluated on the state's latest record
 *   ([me.ahoo.wow.query.QueryGateway.admitRecord]): its history, a version or a trace, is read by its current
 *   attributes.
 * - **Events.** None of its own until each stream records its aggregate's current attributes: the engine pins the
 *   events queries of a governed aggregate, so an aggregate's streams show when its snapshot does
 *   ([me.ahoo.wow.query.events.EventVisibility]).
 * - **Datasets.** A dataset whose scope binds a governed aggregate ([QueryDataset.scope]) reads none of its rows but
 *   for a predicate that grants every aggregate, until its rows are filtered through the aggregate's current
 *   attributes, which the dataset's storage does not keep yet. A dataset bound to no governed aggregate is left to
 *   the default rule.
 *
 * An attribute the aggregate does not have ([AccessPolicies.declares]) matches nothing, and is logged once: the policy
 * fails closed.
 */
@InternalWowApi
class AccessQueryPolicy(private val access: AccessPolicies) : QueryPolicy {
    private val undeclared = ConcurrentHashMap.newKeySet<Pair<AggregateType, String>>()

    /** Right after the scope, before the application's policies. */
    override val order: Int = ScopeQueryPolicy.ORDER + 1

    /** Its rule reads the state: the engine pins the events queries of the aggregates it governs. */
    override val events: EventsMode
        get() = EventsMode.PINNED

    override suspend fun evaluate(caller: QueryCaller, context: QueryContext<*>): FilterExpression {
        val acting = caller.caller
        if (acting == null || acting.service) {
            return MatchAllFilter
        }
        val profile = context.schema.profile
        if (profile is DatasetQueryModelProfile) {
            val bound = profile.dataset.scope.access?.aggregate ?: return MatchAllFilter
            val policy = access.of(bound) ?: return MatchAllFilter
            // The rows are not yet filtered through the aggregate's current attributes: only a grant of all reads.
            return AccessFilters.compile(policy.read(acting)) { null }
        }
        val type = context.aggregateType
        val policy = access.of(type) ?: return MatchAllFilter
        return when (context.schema.model) {
            QueryModel.SNAPSHOT -> snapshots(type, policy.read(acting), profile)
            QueryModel.EVENTS -> MatchAllFilter
            // Records the policy cannot be evaluated on.
            else -> MatchNoneFilter
        }
    }

    private fun snapshots(
        type: AggregateType,
        predicate: AccessPredicate,
        profile: QueryModelProfile?,
    ): FilterExpression {
        val filter = AccessFilters.compile(predicate) { attribute -> fieldOf(type, attribute, profile) }
        return when (filter) {
            MatchNoneFilter -> MatchNoneFilter
            MatchAllFilter -> RECORDED
            else -> AndFilter(listOf(RECORDED, filter))
        }
    }

    /** The field [attribute] of [type] is stored under on its snapshots, or null when [type] does not have it. */
    private fun fieldOf(
        type: AggregateType,
        attribute: AccessAttribute<*, *>,
        profile: QueryModelProfile?,
    ): QueryField<*>? {
        if (!access.declares(type, attribute)) {
            if (undeclared.add(type to attribute.name)) {
                logger.warn {
                    "The access policy of $type compares ${attribute.name}, which $type does not have as " +
                        "$attribute: that comparison matches nothing."
                }
            }
            return null
        }
        return when (attribute) {
            AccessAttribute.OWNER -> systemField(profile, SystemField.OWNER_ID)
            AccessAttribute.SPACE -> systemField(profile, SystemField.SPACE_ID)
            else -> QueryField("${SnapshotFields.ACCESS}.${attribute.name}")
        }
    }

    private fun systemField(profile: QueryModelProfile?, field: SystemField): QueryField<*> =
        profile?.systemField(field) ?: QueryModelProfile.metadataField(field)

    private companion object {
        val logger = KotlinLogging.logger {}

        /** A snapshot whose access attributes are recorded. */
        val RECORDED = IsNotNullFilter(QueryField(SnapshotFields.ACCESS))
    }
}
