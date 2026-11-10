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
import me.ahoo.wow.api.access.resolveAccessPolicies
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
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
import me.ahoo.wow.query.dataset.DatasetAccess
import me.ahoo.wow.query.dataset.DatasetQueryModelProfile
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.StorageSupport
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.json.query.EventStreamFields
import me.ahoo.wow.spi.json.query.SnapshotFields
import java.util.concurrent.ConcurrentHashMap
import me.ahoo.wow.api.aggregate.Ownership as AggregateOwnership

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

    /**
     * The owner and space of [scope], a caller's, that every read of [type] holds the caller to (its snapshot and
     * events queries, its state reads, in process and over HTTP): none where a policy of its own governs [type], which
     * decides them instead; otherwise the part of [scope] [ownership], [type]'s, keeps.
     */
    fun scopeOf(type: AggregateType, ownership: AggregateOwnership, scope: Scope): Scope =
        if (governs(type)) Scope.NONE else ownership.scopeOf(scope)

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
            val byType = resolveAccessPolicies(
                policies,
                aggregates.keys,
                aggregatesOf,
                aggregates.mapValues { (_, aggregate) -> aggregate.access.map { it.name } },
                strict,
            )
            return AccessPolicies(byType, aggregates)
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
 * - **Events.** The same predicate on what each stream records of its aggregate's current attributes, `access`, the
 *   owner and the space among them (`access.owner`, `access.space`): a stream is stamped again whenever its aggregate's
 *   attributes change, so the events of an aggregate show while it shows, whichever commit wrote them. A stream not
 *   yet stamped (`access` null) is never read. An events query need not name its aggregates.
 * - **Datasets.** A dataset whose scope binds a governed aggregate ([QueryDataset.scope]) reads the rows whose
 *   aggregate the predicate lets the caller read by that aggregate's current attributes, whatever a row recorded when
 *   it was written: the whole predicate is one restriction ([DatasetAccess]), which the dataset's storage compiles as
 *   one semi-join of the dataset's id column on the aggregates' current access records (on ClickHouse, the Sink's
 *   `_state`). A row whose aggregate the storage holds no attributes of is never read. A predicate that grants every
 *   aggregate restricts nothing and reads no access record; on a storage that does not declare
 *   [StorageSupport.datasetAccess] any other predicate reads none of the rows, logged once. A dataset bound to no
 *   governed aggregate is left to the default rule.
 *
 * An attribute the aggregate does not have ([AccessPolicies.declares]) matches nothing, and is logged once: the policy
 * fails closed. Between a change of an aggregate's attributes and the stamp of its earlier streams, an events query
 * reads those streams by the attributes they were stamped with; its state reads are never behind.
 */
@InternalWowApi
class AccessQueryPolicy(private val access: AccessPolicies) : QueryPolicy {
    private val undeclared = ConcurrentHashMap.newKeySet<Pair<AggregateType, String>>()
    private val unrestricted = ConcurrentHashMap.newKeySet<AggregateType>()

    /** Right after the scope, before the application's policies. */
    override val order: Int = ScopeQueryPolicy.ORDER + 1

    override suspend fun evaluate(caller: QueryCaller, context: QueryContext<*>): FilterExpression {
        val acting = caller.caller
        if (acting == null || acting.service) {
            return MatchAllFilter
        }
        val profile = context.schema.profile
        if (profile is DatasetQueryModelProfile) {
            val bound = profile.dataset.scope.access?.aggregate ?: return MatchAllFilter
            val policy = access.of(bound) ?: return MatchAllFilter
            return dataset(profile.dataset, bound, policy.read(acting), context.schema.storage)
        }
        val type = context.aggregateType
        val policy = access.of(type) ?: return MatchAllFilter
        // Any model of an aggregate other than its events is its snapshots'.
        val fields = if (context.schema.model == QueryModel.EVENTS) STREAMS else SNAPSHOTS
        return compile(type, policy.read(acting), fields)
    }

    /** [predicate] of [type] on the records whose attributes are under [fields], each with its attributes recorded. */
    private fun compile(type: AggregateType, predicate: AccessPredicate, fields: AccessFields): FilterExpression {
        val filter = AccessFilters.compile(predicate) { attribute ->
            fields.of(attribute).takeIf { declares(type, attribute) }
        }
        return when (filter) {
            MatchNoneFilter -> MatchNoneFilter
            MatchAllFilter -> fields.recorded
            else -> AndFilter(listOf(fields.recorded, filter))
        }
    }

    /**
     * [predicate] of [type] on the rows of [dataset], each read by its aggregate's current access record: the one
     * restriction of [DatasetAccess], none for a grant of every aggregate, and nothing on a [storage] that cannot read
     * the records.
     */
    private fun dataset(
        dataset: QueryDataset,
        type: AggregateType,
        predicate: AccessPredicate,
        storage: StorageSupport,
    ): FilterExpression {
        val filter = AccessFilters.compile(predicate) { attribute ->
            DatasetAccess.fieldOf(attribute).takeIf { declares(type, attribute) }
        }
        if (filter === MatchAllFilter || filter === MatchNoneFilter) return filter
        if (storage.datasetAccess == SupportMode.NONE) {
            if (unrestricted.add(dataset.name)) {
                logger.warn {
                    "The storage of dataset ${dataset.name} cannot read the access attributes of $type, which its " +
                        "rows are of: its rows are read only by a policy that grants every aggregate."
                }
            }
            return MatchNoneFilter
        }
        return DatasetAccess.restriction(filter)
    }

    /** Whether [type] has [attribute], logging once a comparison of one it does not have. */
    private fun declares(type: AggregateType, attribute: AccessAttribute<*, *>): Boolean {
        if (!access.declares(type, attribute)) {
            if (undeclared.add(type to attribute.name)) {
                logger.warn {
                    "The access policy of $type compares ${attribute.name}, which $type does not have as " +
                        "$attribute: that comparison matches nothing."
                }
            }
            return false
        }
        return true
    }

    /**
     * Where a model's records hold an aggregate's access attributes: each declared one under [access], the built-in
     * ones under [owner] and [space]; [recorded] holds of a record whose attributes are recorded.
     */
    private class AccessFields(
        private val access: String,
        private val owner: QueryField<*>,
        private val space: QueryField<*>,
    ) {
        val recorded = IsNotNullFilter(QueryField(access))

        fun of(attribute: AccessAttribute<*, *>): QueryField<*> = when (attribute) {
            AccessAttribute.OWNER -> owner
            AccessAttribute.SPACE -> space
            else -> QueryField("$access.${attribute.name}")
        }
    }

    private companion object {
        val logger = KotlinLogging.logger {}

        /** A snapshot's: its owner and space are the snapshot's own. */
        val SNAPSHOTS = AccessFields(
            SnapshotFields.ACCESS,
            QueryField(SnapshotFields.OWNER_ID),
            QueryField(SnapshotFields.SPACE_ID),
        )

        /** A stream's: its owner and space among its access attributes, the aggregate's current ones. */
        val STREAMS = AccessFields(
            EventStreamFields.ACCESS,
            QueryField("${EventStreamFields.ACCESS}.${AccessValues.OWNER}"),
            QueryField("${EventStreamFields.ACCESS}.${AccessValues.SPACE}"),
        )
    }
}
