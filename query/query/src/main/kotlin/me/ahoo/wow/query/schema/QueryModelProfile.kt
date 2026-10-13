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

package me.ahoo.wow.query.schema

import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.spec.SystemField
import me.ahoo.wow.query.spec.spec
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Record layout and invariants of one [QueryModel]: the built-in Snapshot, and each declared dataset
 * ([DatasetQueryModelProfile][me.ahoo.wow.query.dataset.DatasetQueryModelProfile]). The hierarchy is closed to this
 * module: its constructor is internal.
 *
 * Every model difference that the query runtime depends on lives here, so gateways, validators and backends ask the
 * profile instead of branching on [QueryModel] themselves. A schema carries its profile ([QueryModelSchema.profile]);
 * other custom models have none, and operations that need one of these facts reject them.
 */
abstract class QueryModelProfile internal constructor(val model: QueryModel) {
    /**
     * Unique record identity: the target of id filters and the tie-breaker of every cursor; `null` when the model
     * declares none, which rejects both.
     */
    abstract val identityField: QueryField?

    /**
     * Logical field that holds the user payload; schema extensions must live below it. `null` when the whole record is
     * payload, as in a dataset.
     */
    abstract val payloadField: QueryField?

    /**
     * The fields that identify one record across the whole model, in the order a cursor appends them as its
     * tie-breakers: the [identityField] alone by default, or nothing when the model has none.
     */
    open val cursorUniqueKey: List<QueryField>
        get() = listOfNotNull(identityField)

    /** When the record last changed: the default `orderBy` of FIRST and LAST metrics; `null` when there is none. */
    abstract val eventTimeField: QueryField?

    /** When the record's aggregate got its first event, or `null` when a record does not say. */
    open val firstEventTimeField: QueryField? = null

    /**
     * The field an authenticated caller scope must pin when
     * [requireAuthenticatedScope][me.ahoo.wow.query.QueryEntryPolicy.requireAuthenticatedScope] is on: the tenant,
     * the isolation boundary of every built-in model.
     */
    abstract val requiredScope: QueryField

    /**
     * The one tenant every record belongs to, for a model with no tenant field (a platform-level dataset); `null`
     * otherwise. A tenant filter then matches every record when it names this tenant and none when it names another,
     * and it pins [requiredScope].
     */
    open val fixedTenantId: String? = null

    /**
     * The logical field a system-field filter (ID, TENANT_ID, DELETION, ...) targets: [identityField] for the record
     * identity; `null` when the model does not carry [field].
     */
    abstract fun systemField(field: SystemField): QueryField?

    /** Fields that every record of this model carries, independent of the aggregate. */
    abstract val systemDeclaration: QuerySchemaDeclaration

    /**
     * Returns the scope appended to a query whose [filter] does not scope it explicitly,
     * or [MatchAllFilter] when the model has no implicit scope.
     */
    open fun defaultScope(filter: FilterExpression): FilterExpression = MatchAllFilter

    /** Rejects a projection that would return records the model cannot interpret. */
    open fun validateProjection(projection: Projection) = Unit

    companion object {
        /** The record metadata field of [field], common to every model; the identity is the model's own. */
        internal fun metadataField(field: SystemField): QueryField = when (field) {
            SystemField.IDENTITY -> error("The record identity is defined by each model.")
            SystemField.AGGREGATE_ID -> AGGREGATE_ID
            SystemField.TENANT_ID -> TENANT_ID
            SystemField.OWNER_ID -> OWNER_ID
            SystemField.SPACE_ID -> SPACE_ID
            SystemField.DELETED -> DELETED
        }

        private val AGGREGATE_ID = QueryField(SnapshotFields.AGGREGATE_ID)
        private val TENANT_ID = QueryField(SnapshotFields.TENANT_ID)
        private val OWNER_ID = QueryField(SnapshotFields.OWNER_ID)
        private val SPACE_ID = QueryField(SnapshotFields.SPACE_ID)
        private val DELETED = QueryField(SnapshotFields.DELETED)

        /** Returns the profile of a built-in [model], or `null` for a custom model. */
        fun of(model: QueryModel): BuiltInQueryModelProfile? = when (model) {
            QueryModel.SNAPSHOT -> SnapshotQueryModelProfile
            else -> null
        }
    }
}

/**
 * The profile of an aggregate's read model: every record carries an identity, a payload, an event time and every
 * system field. The V10 `events` model (decision D131) is work package 6.2c's.
 */
sealed class BuiltInQueryModelProfile(model: QueryModel) : QueryModelProfile(model) {
    abstract override val identityField: QueryField
    abstract override val payloadField: QueryField
    abstract override val eventTimeField: QueryField

    final override val requiredScope: QueryField
        get() = metadataField(SystemField.TENANT_ID)

    final override fun systemField(field: SystemField): QueryField = when (field) {
        SystemField.IDENTITY -> identityField
        else -> metadataField(field)
    }
}

/**
 * The names of a snapshot record's fields as the query side reads them (decision D128): one record per aggregate, the
 * columns of `me.ahoo.wow.spi.snapshot.SnapshotRecord` with the state under [STATE]. 9.x's `contextName`,
 * `aggregateName`, `eventId` and `snapshotTime` are gone, and 9.x's `eventTime` is [LAST_EVENT_TIME]: a snapshot query
 * is always of one aggregate type, which the backend adds itself (decision D131). [OPERATOR] and [FIRST_OPERATOR] keep
 * 9.x's names (decision D163) but are objects, `{id, tenantId, crossScope}` ([OperatorFields]), or null for an
 * anonymous caller: filter on `operator.id`.
 */
object SnapshotFields {
    const val TENANT_ID: String = "tenantId"
    const val AGGREGATE_ID: String = "aggregateId"
    const val VERSION: String = "version"
    const val OWNER_ID: String = "ownerId"
    const val SPACE_ID: String = "spaceId"
    const val DELETED: String = "deleted"
    const val TAGS: String = "tags"
    const val FIRST_EVENT_TIME: String = "firstEventTime"
    const val LAST_EVENT_TIME: String = "lastEventTime"
    const val OPERATOR: String = "operator"
    const val FIRST_OPERATOR: String = "firstOperator"
    const val STATE: String = "state"
}

/** The properties of an operator system field ([SnapshotFields.OPERATOR], [SnapshotFields.FIRST_OPERATOR]). */
object OperatorFields {
    const val ID: String = "id"
    const val TENANT_ID: String = "tenantId"
    const val CROSS_SCOPE: String = "crossScope"
}

/** Aggregate snapshots: one record per aggregate, the payload under `state` (decision D128). */
data object SnapshotQueryModelProfile : BuiltInQueryModelProfile(QueryModel.SNAPSHOT) {
    override val identityField: QueryField = QueryField(SnapshotFields.AGGREGATE_ID)
    override val payloadField: QueryField = QueryField(SnapshotFields.STATE)
    override val eventTimeField: QueryField = QueryField(SnapshotFields.LAST_EVENT_TIME)
    override val firstEventTimeField: QueryField = QueryField(SnapshotFields.FIRST_EVENT_TIME)

    /**
     * An aggregate id is unique only within its tenant (the snapshot's key is `(tenantId, aggregateId)`), so a cursor
     * orders by the tenant before the id: a cross-tenant cursor (in-process or trusted) cannot skip a record that
     * shares an id with one in another tenant.
     */
    override val cursorUniqueKey: List<QueryField> =
        listOf(QueryField(SnapshotFields.TENANT_ID), QueryField(SnapshotFields.AGGREGATE_ID))
    override val systemDeclaration: QuerySchemaDeclaration = QuerySchemaDeclaration(
        Collections.unmodifiableMap(
            linkedMapOf(
                SnapshotFields.TENANT_ID.stringField(),
                SnapshotFields.AGGREGATE_ID.stringField(),
                SnapshotFields.VERSION.integerField(),
                // A snapshot without an owner or a space holds null there (Scope.NONE).
                SnapshotFields.OWNER_ID.stringField(nullable = true),
                SnapshotFields.SPACE_ID.stringField(nullable = true),
                SnapshotFields.DELETED.booleanField(),
                SnapshotFields.TAGS.objectField(dynamic = true).let { (field, declaration) ->
                    field to declaration.copy(
                        additionalProperties = DeclarationValue.Set(
                            QueryFieldDeclaration(
                                nullable = DeclarationValue.Set(false),
                                items = DeclarationValue.Set(
                                    QueryFieldDeclaration(
                                        valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)),
                                        nullable = DeclarationValue.Set(false),
                                    )
                                ),
                            )
                        )
                    )
                },
                SnapshotFields.FIRST_EVENT_TIME.epochField(),
                SnapshotFields.LAST_EVENT_TIME.epochField(),
                // The operators of the last and the first stream; null when that caller was anonymous (D163).
                SnapshotFields.OPERATOR.operatorField(),
                SnapshotFields.FIRST_OPERATOR.operatorField(),
                SnapshotFields.STATE.payloadField(),
            ),
        ),
    )

    /** Snapshot queries see active aggregates unless the filter selects a deletion state itself. */
    override fun defaultScope(filter: FilterExpression): FilterExpression =
        if (filter.hasDeletionScope()) MatchAllFilter else DeletionFilter(DeletionState.ACTIVE)

    private fun FilterExpression.hasDeletionScope(): Boolean =
        spec.systemField == SystemField.DELETED || (this is AndFilter && operands.any { it.hasDeletionScope() })
}

/**
 * The ordering field of a FIRST / LAST [metric]: its own `orderBy`, or the model's event time when the metric sits at
 * the record level ([scope] is `null`). Inside an element, and on a custom model, `orderBy` must be named.
 */
fun QueryModelSchema.firstLastOrderBy(metric: AggregationMetric.Edge, scope: QueryField?): QueryField =
    metric.orderBy ?: profile?.eventTimeField?.takeIf { scope == null }
        ?: throw QueryViolation.FirstLastRequiresOrderBy(QueryField(metric.alias)).rejection()

/**
 * The logical field a system-field filter targets on this schema's model ([QueryModelProfile.systemField]). A model
 * without an identity rejects an identity filter; a profile that does not carry another system field rejects its
 * filter as naming an unknown field. A custom model without a profile shares the record metadata fields.
 */
fun QueryModelSchema.systemField(field: SystemField): QueryField {
    if (field == SystemField.IDENTITY) return requireIdentityField()
    val profile = profile ?: return QueryModelProfile.metadataField(field)
    return profile.systemField(field)
        ?: throw QueryViolation.UnknownField(QueryModelProfile.metadataField(field)).rejection()
}

/**
 * The fields a cursor of this schema's model appends as its tie-breakers ([QueryModelProfile.cursorUniqueKey]): those
 * of the profile's key the schema carries, the identity always, rejecting custom models that define no identity. Every
 * snapshot schema the Catalog compiles carries the tenant (a system field); a schema built without it, as tests and
 * tools may build one, orders by the identity alone.
 */
fun QueryModelSchema.cursorUniqueKey(): List<QueryField> {
    val identity = requireIdentityField()
    val key = profile?.cursorUniqueKey ?: return listOf(identity)
    return key.filter { it == identity || field(it) != null }
}

/** Returns the record identity of this schema's model, rejecting custom models that define none. */
fun QueryModelSchema.requireIdentityField(): QueryField = profile?.identityField
    ?: throw QueryViolation.IdentityUndefined(model).rejection()

private fun String.payloadField() = QueryField(this) to QueryFieldDeclaration(
    nullable = DeclarationValue.Set(false),
    required = DeclarationValue.Set(true),
)

private fun String.stringField(nullable: Boolean = false) = field(QueryValueType.STRING, nullable = nullable)

private fun String.integerField() = field(QueryValueType.INTEGER)

private fun String.booleanField() = field(QueryValueType.BOOLEAN)

private fun String.objectField(dynamic: Boolean = false) = field(
    QueryValueType.OBJECT
).let { (field, declaration) ->
    field to declaration.copy(
        additionalProperties = if (dynamic) DeclarationValue.Set(QueryFieldDeclaration()) else DeclarationValue.Unset
    )
}

private fun String.operatorField() = QueryField(this) to QueryFieldDeclaration(
    valueTypes = DeclarationValue.Set(setOf(QueryValueType.OBJECT)),
    nullable = DeclarationValue.Set(true),
    required = DeclarationValue.Set(true),
    properties = DeclarationValue.Set(
        linkedMapOf(
            OperatorFields.ID to scalarProperty(QueryValueType.STRING),
            OperatorFields.TENANT_ID to scalarProperty(QueryValueType.STRING),
            OperatorFields.CROSS_SCOPE to scalarProperty(QueryValueType.BOOLEAN),
        ),
    ),
)

private fun scalarProperty(valueType: QueryValueType) = QueryFieldDeclaration(
    valueTypes = DeclarationValue.Set(setOf(valueType)),
    nullable = DeclarationValue.Set(false),
    required = DeclarationValue.Set(true),
)

private fun String.epochField() = field(
    QueryValueType.INTEGER,
    DeclarationValue.Set(Temporal.Epoch(TimeUnit.MILLISECONDS)),
)

private fun String.field(
    valueType: QueryValueType,
    semanticType: DeclarationValue<QuerySemanticType?> = DeclarationValue.Unset,
    nullable: Boolean = false,
): Pair<QueryField, QueryFieldDeclaration> = QueryField(this) to QueryFieldDeclaration(
    valueTypes = DeclarationValue.Set(setOf(valueType)),
    nullable = DeclarationValue.Set(nullable),
    required = DeclarationValue.Set(true),
    semanticType = semanticType,
)
