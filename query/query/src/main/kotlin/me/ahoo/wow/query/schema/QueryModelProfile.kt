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

import me.ahoo.wow.api.annotation.InternalWowApi
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
import me.ahoo.wow.spi.json.query.OperatorFields
import me.ahoo.wow.spi.json.query.SnapshotFields
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
@InternalWowApi
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
     * [requireAuthenticatedScope][me.ahoo.wow.query.policy.QueryEntryPolicy.requireAuthenticatedScope] is on: the tenant,
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
    @InternalWowApi
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

    /**
     * Whether the schema's response masks mask this model's records; `false` for a model whose gateway masks each
     * record by its own rule (the `events` model masks each event's body by its event's schema). Masked fields stay
     * protected from comparison and aggregation either way.
     */
    internal open val masksResponses: Boolean
        get() = true

    @InternalWowApi
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
            QueryModel.EVENTS -> EventsQueryModelProfile.DEFAULT
            else -> null
        }
    }
}

/**
 * The profile of an aggregate's read model: every record carries an identity, a payload and an event time: the
 * snapshots ([SnapshotQueryModelProfile]) and the event streams ([EventsQueryModelProfile]).
 */
@InternalWowApi
sealed class BuiltInQueryModelProfile(model: QueryModel) : QueryModelProfile(model) {
    abstract override val identityField: QueryField
    abstract override val payloadField: QueryField
    abstract override val eventTimeField: QueryField

    final override val requiredScope: QueryField
        get() = metadataField(SystemField.TENANT_ID)

    @InternalWowApi
    final override fun systemField(field: SystemField): QueryField = when (field) {
        SystemField.IDENTITY -> identityField
        else -> metadataField(field)
    }
}

/** Aggregate snapshots: one record per aggregate, the payload under `state`. */
internal data object SnapshotQueryModelProfile : BuiltInQueryModelProfile(QueryModel.SNAPSHOT) {
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
                SnapshotFields.TAGS.tagsField(),
                SnapshotFields.FIRST_EVENT_TIME.epochField(),
                SnapshotFields.LAST_EVENT_TIME.epochField(),
                // The operators of the last and the first stream; null when that caller was anonymous.
                SnapshotFields.OPERATOR.operatorField(),
                SnapshotFields.FIRST_OPERATOR.operatorField(),
                // The schedules the aggregate keeps, each rule by its key.
                SnapshotFields.SCHEDULES.schedulesField(),
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
internal fun QueryModelSchema.firstLastOrderBy(metric: AggregationMetric.Edge, scope: QueryField?): QueryField =
    metric.orderBy ?: profile?.eventTimeField?.takeIf { scope == null }
        ?: throw QueryViolation.FirstLastRequiresOrderBy(QueryField(metric.alias)).rejection()

/**
 * The logical field a system-field filter targets on this schema's model ([QueryModelProfile.systemField]). A model
 * without an identity rejects an identity filter; a profile that does not carry another system field rejects its
 * filter as naming an unknown field. A custom model without a profile shares the record metadata fields.
 */
internal fun QueryModelSchema.systemField(field: SystemField): QueryField {
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
internal fun QueryModelSchema.cursorUniqueKey(): List<QueryField> {
    val identity = requireIdentityField()
    val key = profile?.cursorUniqueKey ?: return listOf(identity)
    return key.filter { it == identity || field(it) != null }
}

/** Returns the record identity of this schema's model, rejecting custom models that define none. */
internal fun QueryModelSchema.requireIdentityField(): QueryField = profile?.identityField
    ?: throw QueryViolation.IdentityUndefined(model).rejection()

internal fun String.payloadField() = QueryField(this) to QueryFieldDeclaration(
    nullable = DeclarationValue.Set(false),
    required = DeclarationValue.Set(true),
)

internal fun String.stringField(nullable: Boolean = false) = field(QueryValueType.STRING, nullable = nullable)

internal fun String.integerField() = field(QueryValueType.INTEGER)

private fun String.booleanField() = field(QueryValueType.BOOLEAN)

/**
 * A tags system field: an object of each tag's values by its key, each an array of strings; [nullable]
 * when a record may not have recorded them (an event stream appended before its log learned them).
 */
internal fun String.tagsField(nullable: Boolean = false) = field(
    QueryValueType.OBJECT,
    nullable = nullable,
).let { (field, declaration) ->
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
        ),
    )
}

internal fun String.operatorField() = QueryField(this) to QueryFieldDeclaration(
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

/**
 * The schedules system field: an object of each rule by its key, `{type, ...}`: `type` (`at`,
 * `after`, `cron`), and the parts of its kind, `epochMillis`, `millis`, or `expression`, `zone` and `misfire`.
 */
private fun String.schedulesField() = field(QueryValueType.OBJECT).let { (field, declaration) ->
    field to declaration.copy(
        additionalProperties = DeclarationValue.Set(
            QueryFieldDeclaration(
                valueTypes = DeclarationValue.Set(setOf(QueryValueType.OBJECT)),
                nullable = DeclarationValue.Set(false),
                properties = DeclarationValue.Set(
                    linkedMapOf(
                        ScheduleFields.TYPE to scalarProperty(QueryValueType.STRING),
                        ScheduleFields.EPOCH_MILLIS to optionalProperty(QueryValueType.INTEGER),
                        ScheduleFields.MILLIS to optionalProperty(QueryValueType.INTEGER),
                        ScheduleFields.EXPRESSION to optionalProperty(QueryValueType.STRING),
                        ScheduleFields.ZONE to optionalProperty(QueryValueType.STRING),
                        ScheduleFields.MISFIRE to optionalProperty(QueryValueType.STRING),
                    ),
                ),
            ),
        ),
    )
}

/** The properties of a rule of the schedules system field ([SnapshotFields.SCHEDULES]), as Wow's JSON writes it. */
internal object ScheduleFields {
    const val TYPE: String = "type"
    const val EPOCH_MILLIS: String = "epochMillis"
    const val MILLIS: String = "millis"
    const val EXPRESSION: String = "expression"
    const val ZONE: String = "zone"
    const val MISFIRE: String = "misfire"
}

private fun optionalProperty(valueType: QueryValueType) = QueryFieldDeclaration(
    valueTypes = DeclarationValue.Set(setOf(valueType)),
    nullable = DeclarationValue.Set(false),
    required = DeclarationValue.Set(false),
)

internal fun scalarProperty(valueType: QueryValueType) = QueryFieldDeclaration(
    valueTypes = DeclarationValue.Set(setOf(valueType)),
    nullable = DeclarationValue.Set(false),
    required = DeclarationValue.Set(true),
)

internal fun String.epochField() = field(
    QueryValueType.INTEGER,
    DeclarationValue.Set(Temporal.Epoch(TimeUnit.MILLISECONDS)),
)

internal fun String.field(
    valueType: QueryValueType,
    semanticType: DeclarationValue<QuerySemanticType?> = DeclarationValue.Unset,
    nullable: Boolean = false,
): Pair<QueryField, QueryFieldDeclaration> = QueryField(this) to QueryFieldDeclaration(
    valueTypes = DeclarationValue.Set(setOf(valueType)),
    nullable = DeclarationValue.Set(nullable),
    required = DeclarationValue.Set(true),
    semanticType = semanticType,
)
