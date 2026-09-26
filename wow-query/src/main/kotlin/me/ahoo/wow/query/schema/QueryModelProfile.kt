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
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.api.query.spec.SystemField
import me.ahoo.wow.query.QueryExecutionException
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.event.DomainEventRecords
import me.ahoo.wow.serialization.state.SnapshotRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Record layout and invariants of one built-in [QueryModel].
 *
 * Every snapshot/event-stream difference that the query runtime depends on lives here, so gateways,
 * validators, maskers and backends ask the profile instead of branching on [QueryModel] themselves.
 * Custom models have no profile; operations that need one of these facts reject them.
 */
sealed class QueryModelProfile(val model: QueryModel) {
    /** Unique record identity: the target of id filters and the tie-breaker of every cursor. */
    abstract val identityField: QueryField

    /** Logical field that holds the user payload; masked fields must live below it. */
    abstract val payloadField: QueryField

    /** Field that names the payload type of each record, or `null` when the payload is monomorphic. */
    open val payloadTypeField: QueryField? = null

    /** When the record last changed: the default `orderBy` of FIRST and LAST metrics. */
    abstract val eventTimeField: QueryField

    /**
     * The field an authenticated caller scope must pin when
     * [requireAuthenticatedScope][me.ahoo.wow.query.QueryEntryPolicy.requireAuthenticatedScope] is on: the tenant,
     * the isolation boundary of every built-in model.
     */
    val requiredScope: QueryField
        get() = systemField(SystemField.TENANT_ID)

    /**
     * The logical field a system-field filter (ID, TENANT_ID, DELETION, ...) targets: [identityField] for the record
     * identity, the shared record metadata otherwise.
     */
    fun systemField(field: SystemField): QueryField = when (field) {
        SystemField.IDENTITY -> identityField
        else -> metadataField(field)
    }

    /** The element whose variants are typed payloads (an event stream's `body`), or `null` for a monomorphic payload. */
    open val variantElement: QueryField? = null

    /** Fields that every record of this model carries, independent of the aggregate. */
    abstract val systemDeclaration: QuerySchemaDeclaration

    /**
     * Returns the scope appended to a query whose [filter] does not scope it explicitly,
     * or [MatchAllFilter] when the model has no implicit scope.
     */
    open fun defaultScope(filter: FilterExpression): FilterExpression = MatchAllFilter

    /** Rejects a projection that would return records the model cannot interpret. */
    open fun validateProjection(projection: Projection) = Unit

    /** Fails, as a server fault, on a stored [record] whose payload types are not among the [declared] ones. */
    internal open fun requireDeclaredPayloadTypes(record: ObjectNode, declared: Set<String>) = Unit

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

        private val AGGREGATE_ID = QueryField(MessageRecords.AGGREGATE_ID)
        private val TENANT_ID = QueryField(MessageRecords.TENANT_ID)
        private val OWNER_ID = QueryField(MessageRecords.OWNER_ID)
        private val SPACE_ID = QueryField(MessageRecords.SPACE_ID)
        private val DELETED = QueryField(StateAggregateRecords.DELETED)

        /** Returns the profile of a built-in [model], or `null` for a custom model. */
        fun of(model: QueryModel): QueryModelProfile? = when (model) {
            QueryModel.SNAPSHOT -> SnapshotQueryModelProfile
            QueryModel.EVENT_STREAM -> EventStreamQueryModelProfile
            else -> null
        }
    }
}

/** Materialized aggregate snapshots: one record per aggregate, payload under `state`. */
data object SnapshotQueryModelProfile : QueryModelProfile(QueryModel.SNAPSHOT) {
    override val identityField: QueryField = QueryField(MessageRecords.AGGREGATE_ID)
    override val payloadField: QueryField = QueryField(StateAggregateRecords.STATE)
    override val eventTimeField: QueryField = QueryField(StateAggregateRecords.EVENT_TIME)
    override val systemDeclaration: QuerySchemaDeclaration = QuerySchemaDeclaration(
        Collections.unmodifiableMap(
            linkedMapOf(
                MessageRecords.CONTEXT_NAME.stringField(),
                MessageRecords.AGGREGATE_NAME.stringField(),
                MessageRecords.AGGREGATE_ID.stringField(),
                MessageRecords.TENANT_ID.stringField(),
                MessageRecords.OWNER_ID.stringField(),
                MessageRecords.SPACE_ID.stringField(),
                MessageRecords.VERSION.integerField(),
                StateAggregateRecords.EVENT_ID.stringField(),
                StateAggregateRecords.FIRST_OPERATOR.stringField(),
                StateAggregateRecords.OPERATOR.stringField(),
                StateAggregateRecords.FIRST_EVENT_TIME.epochField(),
                StateAggregateRecords.EVENT_TIME.epochField(),
                StateAggregateRecords.STATE.payloadField(),
                StateAggregateRecords.TAGS.objectField(dynamic = true).let { (field, declaration) ->
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
                StateAggregateRecords.DELETED.booleanField(),
                SnapshotRecords.SNAPSHOT_TIME.epochField(),
            ),
        ),
    )

    /** Snapshot queries see active aggregates unless the filter selects a deletion state itself. */
    override fun defaultScope(filter: FilterExpression): FilterExpression =
        if (filter.hasDeletionScope()) MatchAllFilter else DeletionFilter(DeletionState.ACTIVE)

    private fun FilterExpression.hasDeletionScope(): Boolean = when (this) {
        is DeletionFilter -> true
        is AndFilter -> operands.any { it.hasDeletionScope() }
        else -> false
    }
}

/** Aggregated domain event streams: one record per command, typed event payloads under `body[].body`. */
data object EventStreamQueryModelProfile : QueryModelProfile(QueryModel.EVENT_STREAM) {
    override val identityField: QueryField = QueryField(MessageRecords.ID)
    override val payloadField: QueryField = QueryField("${MessageRecords.BODY}.${MessageRecords.BODY}")
    override val payloadTypeField: QueryField = QueryField("${MessageRecords.BODY}.${MessageRecords.BODY_TYPE}")
    override val variantElement: QueryField = QueryField(MessageRecords.BODY)
    override val eventTimeField: QueryField = QueryField(MessageRecords.CREATE_TIME)
    override val systemDeclaration: QuerySchemaDeclaration = QuerySchemaDeclaration(
        Collections.unmodifiableMap(
            linkedMapOf(
                MessageRecords.ID.stringField(),
                MessageRecords.CONTEXT_NAME.stringField(),
                MessageRecords.AGGREGATE_NAME.stringField(),
                MessageRecords.HEADER.objectField(dynamic = true),
                MessageRecords.AGGREGATE_ID.stringField(),
                MessageRecords.TENANT_ID.stringField(),
                MessageRecords.OWNER_ID.stringField(),
                MessageRecords.SPACE_ID.stringField(),
                MessageRecords.COMMAND_ID.stringField(),
                MessageRecords.REQUEST_ID.stringField(),
                MessageRecords.VERSION.integerField(),
                MessageRecords.CREATE_TIME.epochField(),
                QueryField(MessageRecords.BODY) to QueryFieldDeclaration(
                    kind = DeclarationValue.Set(QueryValueKind.ARRAY),
                    nullable = DeclarationValue.Set(false),
                    required = DeclarationValue.Set(true),
                    items = DeclarationValue.Set(
                        QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.OBJECT)))
                    ),
                ),
                "${MessageRecords.BODY}.${MessageRecords.ID}".stringField(),
                "${MessageRecords.BODY}.${MessageRecords.NAME}".stringField(),
                "${MessageRecords.BODY}.${DomainEventRecords.REVISION}".stringField(),
                "${MessageRecords.BODY}.${MessageRecords.BODY_TYPE}".stringField(),
                "${MessageRecords.BODY}.${MessageRecords.BODY}".payloadField(),
            ),
        ),
    )

    /** An event payload is only interpretable together with its `bodyType`. */
    override fun validateProjection(projection: Projection) {
        fun QueryField.selects(other: QueryField) = this == other || other.relativeTo(this) != null
        val payloadSelected = projection.include.isEmpty() || projection.include.any {
            it.selects(payloadField) || payloadField.selects(it)
        }
        val payloadExcluded = projection.exclude.any { it.selects(payloadField) }
        val typeSelected = projection.include.isEmpty() || projection.include.any { it.selects(payloadTypeField) }
        requireValid(
            !payloadSelected || payloadExcluded || typeSelected && projection.exclude.none { it.selects(payloadTypeField) }
        ) {
            QueryViolation.EventProjectionTypeRequired(payloadTypeField)
        }
    }

    override fun requireDeclaredPayloadTypes(record: ObjectNode, declared: Set<String>) {
        val events = record.get(MessageRecords.BODY)?.takeUnless(JsonNode::isNull) ?: return
        if (!events.isArray || events.any { !it.isObject }) {
            throw QueryExecutionException("Event body must contain objects.")
        }
        events.forEach { event ->
            if (event.get(MessageRecords.BODY)?.isNull == false &&
                event.get(MessageRecords.BODY_TYPE)?.stringValue() !in declared
            ) {
                throw QueryExecutionException("Unknown event bodyType.")
            }
        }
    }
}

/** The profile of this schema's model, or `null` for a custom model. */
val QueryModelSchema.profile: QueryModelProfile?
    get() = QueryModelProfile.of(model)

/**
 * The ordering field of a FIRST / LAST [metric]: its own `orderBy`, or the model's event time when the metric sits at
 * the record level ([scope] is `null`). Inside an element, and on a custom model, `orderBy` must be named.
 */
fun QueryModelSchema.firstLastOrderBy(metric: AggregationMetric.Edge, scope: QueryField?): QueryField =
    metric.orderBy ?: profile?.eventTimeField?.takeIf { scope == null }
        ?: throw QueryViolation.FirstLastRequiresOrderBy(QueryField(metric.alias)).rejection()

/**
 * The logical field a system-field filter targets on this schema's model ([QueryModelProfile.systemField]). A custom
 * model shares the record metadata fields but defines no identity, so an identity filter on it is rejected.
 */
fun QueryModelSchema.systemField(field: SystemField): QueryField = profile?.systemField(field)
    ?: if (field == SystemField.IDENTITY) requireIdentityField() else QueryModelProfile.metadataField(field)

/** Returns the record identity of this schema's model, rejecting custom models that define none. */
fun QueryModelSchema.requireIdentityField(): QueryField = profile?.identityField
    ?: throw QueryViolation.IdentityUndefined(model).rejection()

private fun String.payloadField() = QueryField(this) to QueryFieldDeclaration(
    nullable = DeclarationValue.Set(false),
    required = DeclarationValue.Set(true),
)

private fun String.stringField() = field(QueryValueType.STRING)

private fun String.integerField() = field(QueryValueType.INTEGER)

private fun String.booleanField() = field(QueryValueType.BOOLEAN)

private fun String.objectField(dynamic: Boolean = false) = field(
    QueryValueType.OBJECT
).let { (field, declaration) ->
    field to declaration.copy(
        additionalProperties = if (dynamic) DeclarationValue.Set(QueryFieldDeclaration()) else DeclarationValue.Unset
    )
}

private fun String.epochField() = field(
    QueryValueType.INTEGER,
    DeclarationValue.Set(Temporal.Epoch(TimeUnit.MILLISECONDS)),
)

private fun String.field(
    valueType: QueryValueType,
    semanticType: DeclarationValue<QuerySemanticType?> = DeclarationValue.Unset,
): Pair<QueryField, QueryFieldDeclaration> = QueryField(this) to QueryFieldDeclaration(
    valueTypes = DeclarationValue.Set(setOf(valueType)),
    nullable = DeclarationValue.Set(false),
    required = DeclarationValue.Set(true),
    semanticType = semanticType,
)
