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

package me.ahoo.wow.query.internal.planning

import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.internal.model.QueryDocumentKind
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryResultShape
import me.ahoo.wow.query.internal.model.QueryTarget
import me.ahoo.wow.query.internal.normalization.LogicalField
import me.ahoo.wow.query.internal.normalization.NormalizedCondition
import me.ahoo.wow.query.internal.normalization.NormalizedDeletionScope
import me.ahoo.wow.query.internal.normalization.NormalizedProjection
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInput
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInvocation
import me.ahoo.wow.query.internal.normalization.NormalizedRecordQuery
import me.ahoo.wow.query.internal.normalization.NormalizedSort
import me.ahoo.wow.query.internal.normalization.NormalizedSortDirection
import me.ahoo.wow.query.internal.normalization.PathBasis
import me.ahoo.wow.query.internal.normalization.PredicateOperator
import me.ahoo.wow.query.internal.normalization.SearchScope
import me.ahoo.wow.query.internal.normalization.SearchScopeId
import me.ahoo.wow.query.internal.schema.EmptyArraySemantics
import me.ahoo.wow.query.internal.schema.FieldCapability
import me.ahoo.wow.query.internal.schema.LogicalFieldType
import me.ahoo.wow.query.internal.schema.Nullability
import me.ahoo.wow.query.internal.schema.Presence
import me.ahoo.wow.query.internal.schema.QueryDocumentSchema
import me.ahoo.wow.query.internal.schema.QueryFieldId
import me.ahoo.wow.query.internal.schema.QueryFieldSchema
import me.ahoo.wow.query.internal.schema.QuerySearchScopeDefinition
import me.ahoo.wow.query.internal.value.NonEmptyList

internal object PlanningFixtures {
    val target = QueryTarget(
        MaterializedNamedAggregate("sales", "order"),
        QueryDocumentKind.SNAPSHOT,
    )
    val identity = QueryFieldId.System(me.ahoo.wow.query.internal.normalization.SystemFieldKind.IDENTITY)
    val tenant = QueryFieldId.System(me.ahoo.wow.query.internal.normalization.SystemFieldKind.TENANT_ID)
    val deleted = QueryFieldId.System(me.ahoo.wow.query.internal.normalization.SystemFieldKind.DELETED)
    val state = QueryFieldId.Path(listOf("state"))
    val name = QueryFieldId.Path(listOf("state", "name"))
    val amount = QueryFieldId.Path(listOf("state", "amount"))
    val description = QueryFieldId.Path(listOf("state", "description"))
    val createdAt = QueryFieldId.Path(listOf("state", "createdAt"))
    val tags = QueryFieldId.Path(listOf("state", "tags"))
    val items = QueryFieldId.Path(listOf("state", "items"))
    val itemName = QueryFieldId.Path(listOf("state", "items", "name"))
    val itemAttributes = QueryFieldId.Path(listOf("state", "items", "attributes"))
    val itemAttributeName = QueryFieldId.Path(listOf("state", "items", "attributes", "name"))
    val searchScopeId = SearchScopeId("order-description")

    val schema = QueryDocumentSchema(
        target = target,
        fields = listOf(
            field(
                identity,
                LogicalFieldType.Text,
                setOf(PredicateOperator.EQ, PredicateOperator.IN),
                setOf(FieldCapability.EXACT, FieldCapability.SORTABLE),
                aliases = setOf(QueryFieldId.Path(listOf("aggregateId"))),
            ),
            field(
                tenant,
                LogicalFieldType.Text,
                setOf(PredicateOperator.EQ, PredicateOperator.IN),
                setOf(FieldCapability.EXACT),
            ),
            field(
                deleted,
                LogicalFieldType.Boolean,
                setOf(PredicateOperator.IS_TRUE, PredicateOperator.IS_FALSE),
                setOf(FieldCapability.EXACT),
            ),
            field(state, LogicalFieldType.Object),
            field(
                name,
                LogicalFieldType.Text,
                setOf(
                    PredicateOperator.EQ,
                    PredicateOperator.IN,
                    PredicateOperator.CONTAINS,
                    PredicateOperator.STARTS_WITH,
                    PredicateOperator.ENDS_WITH,
                    PredicateOperator.IS_NULL,
                    PredicateOperator.NOT_NULL,
                    PredicateOperator.EXISTS,
                ),
                setOf(
                    FieldCapability.EXACT,
                    FieldCapability.PRESENCE,
                    FieldCapability.LITERAL_PATTERN,
                    FieldCapability.SORTABLE,
                    FieldCapability.PROJECTABLE,
                ),
            ),
            field(
                amount,
                LogicalFieldType.Decimal,
                setOf(
                    PredicateOperator.EQ,
                    PredicateOperator.IN,
                    PredicateOperator.GT,
                    PredicateOperator.GTE,
                    PredicateOperator.LT,
                    PredicateOperator.LTE,
                    PredicateOperator.BETWEEN,
                ),
                setOf(
                    FieldCapability.EXACT,
                    FieldCapability.RANGE,
                    FieldCapability.SORTABLE,
                    FieldCapability.PROJECTABLE,
                    FieldCapability.AGGREGATABLE,
                ),
            ),
            field(
                description,
                LogicalFieldType.Text,
                emptySet(),
                setOf(FieldCapability.FULL_TEXT, FieldCapability.PROJECTABLE),
            ),
            field(
                createdAt,
                LogicalFieldType.Instant,
                setOf(PredicateOperator.EQ, PredicateOperator.GT, PredicateOperator.GTE),
                setOf(
                    FieldCapability.EXACT,
                    FieldCapability.RANGE,
                    FieldCapability.SORTABLE,
                    FieldCapability.PROJECTABLE,
                    FieldCapability.AGGREGATABLE,
                ),
            ),
            field(
                tags,
                LogicalFieldType.Array(
                    elementType = LogicalFieldType.Text,
                    elementNullability = Nullability.NULLABLE,
                    emptySemantics = EmptyArraySemantics.DISTINCT,
                ),
                setOf(
                    PredicateOperator.EQ,
                    PredicateOperator.IN,
                    PredicateOperator.NOT_IN,
                    PredicateOperator.ALL_IN,
                ),
                setOf(FieldCapability.EXACT, FieldCapability.PROJECTABLE),
            ),
            field(
                items,
                LogicalFieldType.Array(
                    elementType = LogicalFieldType.Object,
                    elementNullability = Nullability.NON_NULL,
                    emptySemantics = EmptyArraySemantics.DISTINCT,
                ),
                emptySet(),
                setOf(FieldCapability.ELEMENT_MATCH, FieldCapability.PROJECTABLE),
            ),
            field(
                itemName,
                LogicalFieldType.Text,
                setOf(PredicateOperator.EQ, PredicateOperator.CONTAINS),
                setOf(FieldCapability.EXACT, FieldCapability.LITERAL_PATTERN, FieldCapability.PROJECTABLE),
            ),
            field(
                itemAttributes,
                LogicalFieldType.Array(
                    elementType = LogicalFieldType.Object,
                    elementNullability = Nullability.NON_NULL,
                    emptySemantics = EmptyArraySemantics.DISTINCT,
                ),
                emptySet(),
                setOf(FieldCapability.ELEMENT_MATCH),
            ),
            field(
                itemAttributeName,
                LogicalFieldType.Text,
                setOf(PredicateOperator.EQ),
                setOf(FieldCapability.EXACT),
            ),
        ),
        searchScopes = listOf(
            QuerySearchScopeDefinition(
                id = searchScopeId,
                owner = null,
                fields = NonEmptyList.of(description),
                legacyAliases = setOf(description),
            ),
        ),
    )

    private fun field(
        id: QueryFieldId,
        type: LogicalFieldType,
        operators: Set<PredicateOperator> = emptySet(),
        capabilities: Set<FieldCapability> = emptySet(),
        aliases: Set<QueryFieldId.Path> = emptySet(),
    ): QueryFieldSchema = QueryFieldSchema(
        id = id,
        type = type,
        presence = Presence.OPTIONAL,
        nullability = Nullability.NULLABLE,
        allowedOperators = operators,
        capabilities = capabilities,
        logicalAliases = aliases,
    )

    fun path(vararg segments: String, basis: PathBasis = PathBasis.ROOT): LogicalField.Path =
        LogicalField.Path(segments.asList(), basis)

    fun recordQuery(
        condition: NormalizedCondition = NormalizedCondition.All,
        projection: NormalizedProjection = NormalizedProjection.All,
        sort: List<NormalizedSort> = emptyList(),
        deletionScope: NormalizedDeletionScope = NormalizedDeletionScope.EXPLICIT,
    ): NormalizedRecordQuery = NormalizedRecordQuery(condition, projection, sort, deletionScope)

    fun single(
        query: NormalizedRecordQuery = recordQuery(),
        resultShape: QueryResultShape = QueryResultShape.TYPED,
    ): NormalizedQueryInvocation = NormalizedQueryInvocation(
        target,
        QueryOperation.SINGLE,
        resultShape,
        NormalizedQueryInput.Single(query),
    )

    fun page(
        query: NormalizedRecordQuery = recordQuery(),
        resultShape: QueryResultShape = QueryResultShape.TYPED,
        index: Int = 1,
        size: Int = 20,
        offset: Long = 0,
    ): NormalizedQueryInvocation = NormalizedQueryInvocation(
        target,
        QueryOperation.PAGE,
        resultShape,
        NormalizedQueryInput.Page(query, me.ahoo.wow.query.internal.normalization.NormalizedPage(index, size, offset)),
    )

    fun sort(field: LogicalField, direction: NormalizedSortDirection = NormalizedSortDirection.ASC): NormalizedSort =
        NormalizedSort(field, direction)

    fun legacySearch(field: LogicalField.Path): SearchScope = SearchScope.LegacyField(field)
}
