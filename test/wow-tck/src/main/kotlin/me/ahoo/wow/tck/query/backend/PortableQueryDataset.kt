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

package me.ahoo.wow.tck.query.backend

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.expression.ElementMatchExpression
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.PortableExpression
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.expression.StringComparisonMode
import me.ahoo.wow.api.query.gateway.DeletionScope
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.QueryProjection
import me.ahoo.wow.api.query.gateway.QuerySort
import me.ahoo.wow.api.query.gateway.QuerySortDirection
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.api.query.gateway.RequestedQueryScope
import me.ahoo.wow.query.backend.QueryPortableFeature
import me.ahoo.wow.query.schema.QueryCollectionKind
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryFieldValueKind
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QuerySystemFields
import java.math.BigDecimal
import java.time.Instant
import java.util.Collections

private val PORTABLE_LOGICAL_ID_PATTERN: Regex = Regex("[a-z0-9][a-z0-9-]{0,63}")
private val PORTABLE_LOGICAL_ID: LogicalField = LogicalField("logicalId")

class PortableQueryDocument(
    val logicalId: String,
    val tenantId: String,
    val ownerId: String,
    val spaceId: String,
    val deleted: Boolean,
    fields: Map<LogicalField, QueryValue>
) {
    val fields: Map<LogicalField, QueryValue> = immutableMap(fields)

    init {
        require(PORTABLE_LOGICAL_ID_PATTERN.matches(logicalId)) { "Portable logical id is invalid." }
        require(tenantId.isNotBlank() && ownerId.isNotBlank() && spaceId.isNotBlank()) {
            "Portable document scope values cannot be blank."
        }
        require(PORTABLE_LOGICAL_ID !in this.fields) { "Portable document fields cannot override logicalId." }
    }

    override fun equals(other: Any?): Boolean = other is PortableQueryDocument &&
        logicalId == other.logicalId && tenantId == other.tenantId && ownerId == other.ownerId &&
        spaceId == other.spaceId && deleted == other.deleted && fields == other.fields

    override fun hashCode(): Int = listOf(logicalId, tenantId, ownerId, spaceId, deleted, fields).hashCode()

    override fun toString(): String = "PortableQueryDocument(logicalId=$logicalId, fieldCount=${fields.size})"
}

class PortableStoredQueryDocument(
    val logicalId: String,
    val documentKind: QueryDocumentKind,
    fields: Map<LogicalField, QueryValue>
) {
    val fields: Map<LogicalField, QueryValue> = immutableMap(fields)

    override fun equals(other: Any?): Boolean = other is PortableStoredQueryDocument &&
        logicalId == other.logicalId && documentKind == other.documentKind && fields == other.fields

    override fun hashCode(): Int = 31 * (31 * logicalId.hashCode() + documentKind.hashCode()) + fields.hashCode()

    override fun toString(): String =
        "PortableStoredQueryDocument(logicalId=$logicalId, documentKind=$documentKind, fieldCount=${fields.size})"
}

data class PortableQueryResult @JvmOverloads constructor(
    val logicalId: String = ""
)

// Keeping the complete portable fixture table together makes backend expectations auditable as one contract.
@Suppress("LargeClass", "LongMethod")
object PortableQueryDataset {
    val FULL_TEXT_CAPABILITY: QueryCapabilityId = QueryCapabilityId("full-text")

    val UNSUPPORTED_CAPABILITY: QueryCapabilityId = QueryCapabilityId("x-wow:tck-unsupported")

    val LOGICAL_ID: LogicalField = PORTABLE_LOGICAL_ID

    val NULLABLE_TEXT: LogicalField = LogicalField("nullableText")

    val OPTIONAL_TEXT: LogicalField = LogicalField("optionalText")

    val TITLE: LogicalField = LogicalField("title")

    val SCORE: LogicalField = LogicalField("score")

    val RANK: LogicalField = LogicalField("rank")

    val ENABLED: LogicalField = LogicalField("enabled")

    val OPTIONAL_BOOLEAN: LogicalField = LogicalField("optionalBoolean")

    val STATUS: LogicalField = LogicalField("status")

    val CREATED_AT: LogicalField = LogicalField("createdAt")

    val TAGS: LogicalField = LogicalField("labels")

    val ITEMS: LogicalField = LogicalField("items")

    val ITEM_SKU: LogicalField = LogicalField("items.sku")

    val ITEM_QUANTITY: LogicalField = LogicalField("items.quantity")

    val PROFILE: LogicalField = LogicalField("profile")

    val PROFILE_CITY: LogicalField = LogicalField("profile.city")

    private val PORTABLE_AGGREGATE: NamedAggregate = object : NamedAggregate {
        override val contextName: String = "portable-query"
        override val aggregateName: String = "document"
    }
    private val DELETED_IDS: Set<String> = setOf("d04", "d09")
    private val INSERTION_ORDER: Map<String, Int> = listOf(
        "d02", "d01", "d04", "d03", "d06", "d05", "d08", "d07", "d10", "d09"
    ).withIndex().associate { (index, id) -> id to index }

    val documents: List<PortableQueryDocument> = immutableList(
        createDocuments().sortedBy { document -> INSERTION_ORDER.getValue(document.logicalId) }
    )
    val snapshotDocuments: List<PortableStoredQueryDocument> = immutableList(documents.map(::snapshotDocument))
    val eventStreamDocuments: List<PortableStoredQueryDocument> = immutableList(documents.map(::eventStreamDocument))
    val vectors: List<PortableQueryVector> = immutableList(createVectors())

    fun target(documentKind: QueryDocumentKind): QueryTarget = QueryTarget(PORTABLE_AGGREGATE, documentKind)

    fun schema(documentKind: QueryDocumentKind): QuerySchema = QuerySchema(
        target(documentKind),
        QuerySystemFields.fields(documentKind) + USER_FIELDS
    )

    fun storedDocuments(documentKind: QueryDocumentKind): List<PortableStoredQueryDocument> =
        when (documentKind) {
            QueryDocumentKind.SNAPSHOT -> snapshotDocuments
            QueryDocumentKind.EVENT_STREAM -> eventStreamDocuments
        }

    fun semanticVectors(documentKind: QueryDocumentKind): List<PortableQueryVector> = vectors.filter { vector ->
        vector.expectation(documentKind) != null && when (vector.key) {
            is PortableContractKey.Operator,
            is PortableContractKey.Feature,
            is PortableContractKey.Logical,
            is PortableContractKey.SystemField,
            is PortableContractKey.Scenario -> true

            is PortableContractKey.Operation,
            is PortableContractKey.Lifecycle,
            is PortableContractKey.Capability -> false
        }
    }

    private fun createDocuments(): List<PortableQueryDocument> = listOf(
        document(
            id = "d01",
            scope = Triple("tenant-1", "owner-1", "space-1"),
            fields = fields(
                title = "Alpha.*",
                score = "10.0",
                rank = 1,
                enabled = true,
                status = "NEW",
                day = 1,
                tags = listOf("red", "blue"),
                items = listOf(item("A", 2), item("B", 5)),
                profile = QueryValue.ObjectValue(emptyMap())
            )
        ),
        document(
            id = "d02",
            scope = Triple("tenant-1", "owner-1", "space-2"),
            fields = fields(
                nullableText = QueryValue.NullValue,
                title = "alpha?beta",
                score = "20.0",
                rank = 1,
                enabled = false,
                status = "DONE",
                day = 2,
                tags = emptyList(),
                items = listOf(item("A", 1), item("C", 3)),
                profile = QueryValue.ObjectValue(emptyMap())
            )
        ),
        document(
            id = "d03",
            scope = Triple("tenant-1", "owner-2", "space-1"),
            fields = fields(
                nullableText = string("value"),
                title = "literal.*suffix",
                score = "30.0",
                rank = 2,
                enabled = true,
                status = "PROCESSING",
                day = 3,
                tags = listOf("green"),
                items = listOf(item("B", 2)),
                profile = QueryValue.ObjectValue(mapOf("city" to string("杭州")))
            )
        ),
        document(
            id = "d04",
            scope = Triple("tenant-1", "owner-2", "space-2"),
            deleted = true,
            fields = fields(
                nullableText = string("VALUE"),
                title = "prefix[abc]",
                score = "40.0",
                rank = 2,
                enabled = false,
                status = "NEW",
                day = 4,
                tags = listOf("red"),
                items = listOf(item("A", 4))
            )
        ),
        document(
            id = "d05",
            scope = Triple("tenant-2", "owner-1", "space-1"),
            fields = fields(
                nullableText = string(""),
                title = "你好.*世界",
                score = "50.0",
                rank = 3,
                enabled = true,
                status = "DONE",
                day = 5,
                tags = listOf("red.*"),
                items = listOf(item("A", 1), item("B", 5))
            )
        ),
        document(
            id = "d06",
            scope = Triple("tenant-2", "owner-2", "space-2"),
            fields = fields(
                nullableText = string("suffix"),
                title = "plain*suffix",
                score = "60.0",
                rank = 3,
                enabled = false,
                status = "PROCESSING",
                day = 6,
                tags = listOf("red", "blue", "green"),
                items = listOf(item("A", 5))
            )
        ),
        document(
            id = "d07",
            scope = Triple("tenant-2", "owner-3", "space-3"),
            fields = fields(
                nullableText = string("other"),
                title = "question?literal",
                score = "70.0",
                rank = 4,
                enabled = true,
                status = "NEW",
                day = 7,
                tags = listOf("blue"),
                items = emptyList()
            )
        ),
        document(
            id = "d08",
            scope = Triple("tenant-3", "owner-1", "space-1"),
            fields = fields(
                nullableText = string("Alpha"),
                title = "UPPER.CASE",
                score = "80.0",
                rank = 4,
                enabled = false,
                status = "DONE",
                day = 8,
                tags = listOf("red"),
                items = emptyList()
            )
        ),
        document(
            id = "d09",
            scope = Triple("tenant-3", "owner-2", "space-2"),
            deleted = true,
            fields = fields(
                nullableText = string("omega"),
                title = "dollar\$anchor",
                score = "90.0",
                rank = 5,
                enabled = true,
                status = "PROCESSING",
                day = 9,
                tags = listOf("green"),
                items = listOf(item("C", 9))
            )
        ),
        document(
            id = "d10",
            scope = Triple("tenant-3", "owner-3", "space-3"),
            fields = fields(
                nullableText = string("alpha"),
                title = "alpha.*omega",
                score = "100.0",
                rank = 5,
                enabled = false,
                status = "NEW",
                day = 10,
                tags = emptyList(),
                items = listOf(item("A", 5), item("C", 1)),
                profile = QueryValue.NullValue
            )
        )
    )

    private fun createVectors(): List<PortableQueryVector> {
        val operatorCases = listOf(
            operatorCase(
                PortableOperator.EQ,
                predicate(LOGICAL_ID, PortableOperator.EQ, string("d03")) to ids("d03"),
                predicate(LOGICAL_ID, PortableOperator.EQ, string("missing")) to ids(),
                predicate(TITLE, PortableOperator.EQ, integer(1))
            ),
            operatorCase(
                PortableOperator.NE,
                predicate(NULLABLE_TEXT, PortableOperator.NE, QueryValue.NullValue) to ids("d03", "d04", "d05", "d06", "d07", "d08", "d09", "d10"),
                predicate(OPTIONAL_TEXT, PortableOperator.NE, string("x")) to ids(),
                predicate(RANK, PortableOperator.NE, string("wrong"))
            ),
            operatorCase(
                PortableOperator.GT,
                predicate(SCORE, PortableOperator.GT, decimal("90")) to ids("d10"),
                predicate(SCORE, PortableOperator.GT, decimal("100")) to ids(),
                predicate(SCORE, PortableOperator.GT, string("wrong"))
            ),
            operatorCase(
                PortableOperator.LT,
                predicate(SCORE, PortableOperator.LT, decimal("20")) to ids("d01"),
                predicate(SCORE, PortableOperator.LT, decimal("10")) to ids(),
                predicate(SCORE, PortableOperator.LT, string("wrong"))
            ),
            operatorCase(
                PortableOperator.GTE,
                predicate(SCORE, PortableOperator.GTE, decimal("90")) to ids("d09", "d10"),
                predicate(SCORE, PortableOperator.GTE, decimal("101")) to ids(),
                predicate(SCORE, PortableOperator.GTE, string("wrong"))
            ),
            operatorCase(
                PortableOperator.LTE,
                predicate(SCORE, PortableOperator.LTE, decimal("20")) to ids("d01", "d02"),
                predicate(SCORE, PortableOperator.LTE, decimal("9")) to ids(),
                predicate(SCORE, PortableOperator.LTE, string("wrong"))
            ),
            operatorCase(
                PortableOperator.CONTAINS,
                predicate(TITLE, PortableOperator.CONTAINS, string(".*")) to ids("d01", "d03", "d05", "d10"),
                predicate(TITLE, PortableOperator.CONTAINS, string("^missing\$")) to ids(),
                predicate(TITLE, PortableOperator.CONTAINS, integer(1))
            ),
            operatorCase(
                PortableOperator.IN,
                predicate(NULLABLE_TEXT, PortableOperator.IN, string("value"), string("suffix")) to ids("d03", "d06"),
                predicate(OPTIONAL_TEXT, PortableOperator.IN, string("x")) to ids(),
                predicate(NULLABLE_TEXT, PortableOperator.IN)
            ),
            operatorCase(
                PortableOperator.NOT_IN,
                predicate(NULLABLE_TEXT, PortableOperator.NOT_IN, string("value"), string("suffix")) to
                    ids("d02", "d04", "d05", "d07", "d08", "d09", "d10"),
                predicate(OPTIONAL_TEXT, PortableOperator.NOT_IN, string("x")) to ids(),
                predicate(NULLABLE_TEXT, PortableOperator.NOT_IN)
            ),
            operatorCase(
                PortableOperator.BETWEEN,
                predicate(SCORE, PortableOperator.BETWEEN, decimal("20"), decimal("40")) to ids("d02", "d03", "d04"),
                predicate(SCORE, PortableOperator.BETWEEN, decimal("21"), decimal("29")) to ids(),
                predicate(SCORE, PortableOperator.BETWEEN, decimal("20"))
            ),
            operatorCase(
                PortableOperator.ALL_IN,
                predicate(TAGS, PortableOperator.ALL_IN, string("red"), string("blue")) to ids("d01", "d06"),
                predicate(TAGS, PortableOperator.ALL_IN, string("red"), string("missing")) to ids(),
                predicate(TAGS, PortableOperator.ALL_IN)
            ),
            operatorCase(
                PortableOperator.STARTS_WITH,
                predicate(TITLE, PortableOperator.STARTS_WITH, string("Alpha.*")) to ids("d01"),
                predicate(TITLE, PortableOperator.STARTS_WITH, string("*suffix")) to ids(),
                predicate(TITLE, PortableOperator.STARTS_WITH, integer(1))
            ),
            operatorCase(
                PortableOperator.ENDS_WITH,
                predicate(TITLE, PortableOperator.ENDS_WITH, string(".*omega")) to ids("d10"),
                predicate(TITLE, PortableOperator.ENDS_WITH, string("omega?")) to ids(),
                predicate(TITLE, PortableOperator.ENDS_WITH, integer(1))
            ),
            operatorCase(
                PortableOperator.NULL,
                predicate(NULLABLE_TEXT, PortableOperator.NULL) to ids("d02"),
                predicate(OPTIONAL_TEXT, PortableOperator.NULL) to ids(),
                predicate(NULLABLE_TEXT, PortableOperator.NULL, QueryValue.NullValue)
            ),
            operatorCase(
                PortableOperator.NOT_NULL,
                predicate(NULLABLE_TEXT, PortableOperator.NOT_NULL) to ids("d03", "d04", "d05", "d06", "d07", "d08", "d09", "d10"),
                predicate(OPTIONAL_TEXT, PortableOperator.NOT_NULL) to ids(),
                predicate(NULLABLE_TEXT, PortableOperator.NOT_NULL, QueryValue.NullValue)
            ),
            operatorCase(
                PortableOperator.TRUE,
                predicate(ENABLED, PortableOperator.TRUE) to ids("d01", "d03", "d05", "d07", "d09"),
                predicate(OPTIONAL_BOOLEAN, PortableOperator.TRUE) to ids(),
                predicate(ENABLED, PortableOperator.TRUE, QueryValue.BooleanValue(true))
            ),
            operatorCase(
                PortableOperator.FALSE,
                predicate(ENABLED, PortableOperator.FALSE) to ids("d02", "d04", "d06", "d08", "d10"),
                predicate(OPTIONAL_BOOLEAN, PortableOperator.FALSE) to ids(),
                predicate(ENABLED, PortableOperator.FALSE, QueryValue.BooleanValue(false))
            ),
            operatorCase(
                PortableOperator.EXISTS,
                predicate(NULLABLE_TEXT, PortableOperator.EXISTS, QueryValue.BooleanValue(true)) to
                    ids("d02", "d03", "d04", "d05", "d06", "d07", "d08", "d09", "d10"),
                predicate(OPTIONAL_TEXT, PortableOperator.EXISTS, QueryValue.BooleanValue(true)) to ids(),
                predicate(NULLABLE_TEXT, PortableOperator.EXISTS, string("true"))
            )
        ).flatMap(OperatorCase::vectors)

        return operatorCases + semanticVectors() + logicalVectors() + systemFieldVectors() + operationVectors() +
            lifecycleVectors() + capabilityVectors()
    }

    private fun semanticVectors(): List<PortableQueryVector> = listOf(
        vector(
            "eq-explicit-null-not-missing",
            PortableContractKey.Scenario(PortableQueryScenario.MISSING_VS_NULL),
            PortableVectorKind.BOUNDARY,
            predicate(NULLABLE_TEXT, PortableOperator.EQ, QueryValue.NullValue),
            ids("d02")
        ),
        vector(
            "in-explicit-null-not-missing",
            PortableContractKey.Operator(PortableOperator.IN),
            PortableVectorKind.BOUNDARY,
            predicate(NULLABLE_TEXT, PortableOperator.IN, QueryValue.NullValue),
            ids("d02")
        ),
        vector(
            "not-in-null-not-missing",
            PortableContractKey.Operator(PortableOperator.NOT_IN),
            PortableVectorKind.BOUNDARY,
            predicate(NULLABLE_TEXT, PortableOperator.NOT_IN, QueryValue.NullValue),
            ids("d03", "d04", "d05", "d06", "d07", "d08", "d09", "d10")
        ),
        vector(
            "in-scalar-collection-any-match",
            PortableContractKey.Operator(PortableOperator.IN),
            PortableVectorKind.POSITIVE,
            predicate(TAGS, PortableOperator.IN, string("green")),
            ids("d03", "d06", "d09")
        ),
        vector(
            "not-in-scalar-collection-no-match",
            PortableContractKey.Operator(PortableOperator.NOT_IN),
            PortableVectorKind.NEGATIVE,
            predicate(TAGS, PortableOperator.NOT_IN, string("green")),
            ids("d01", "d02", "d04", "d05", "d07", "d08", "d10")
        ),
        vector(
            "exists-false-only-missing",
            PortableContractKey.Scenario(PortableQueryScenario.MISSING_VS_NULL),
            PortableVectorKind.BOUNDARY,
            predicate(NULLABLE_TEXT, PortableOperator.EXISTS, QueryValue.BooleanValue(false)),
            ids("d01")
        ),
        vector(
            "empty-stored-collection",
            PortableContractKey.Scenario(PortableQueryScenario.EMPTY_COLLECTION),
            PortableVectorKind.BOUNDARY,
            predicate(TAGS, PortableOperator.ALL_IN, string("red")),
            ids("d01", "d04", "d06", "d08")
        ),
        vector(
            "empty-list-exists-present",
            PortableContractKey.Scenario(PortableQueryScenario.EMPTY_COLLECTION),
            PortableVectorKind.BOUNDARY,
            predicate(TAGS, PortableOperator.EXISTS, QueryValue.BooleanValue(true)),
            ids("d01", "d02", "d03", "d04", "d05", "d06", "d07", "d08", "d09", "d10")
        ),
        vector(
            "empty-stored-object-exists",
            PortableContractKey.Scenario(PortableQueryScenario.EMPTY_OBJECT),
            PortableVectorKind.BOUNDARY,
            predicate(PROFILE, PortableOperator.EXISTS, QueryValue.BooleanValue(true)),
            ids("d01", "d02", "d03", "d10")
        ),
        vector(
            "unicode-regex-metacharacters-literal",
            PortableContractKey.Scenario(PortableQueryScenario.UNICODE_LITERAL),
            PortableVectorKind.POSITIVE,
            predicate(
                TITLE,
                PortableOperator.CONTAINS,
                string("你好.*"),
                stringComparison = StringComparisonMode.CASE_SENSITIVE
            ),
            ids("d05")
        ),
        vector(
            "case-sensitive-literal",
            PortableContractKey.Scenario(PortableQueryScenario.CASE_SENSITIVE),
            PortableVectorKind.BOUNDARY,
            predicate(
                NULLABLE_TEXT,
                PortableOperator.CONTAINS,
                string("alpha"),
                stringComparison = StringComparisonMode.CASE_SENSITIVE
            ),
            ids("d10")
        ),
        vector(
            "case-insensitive-literal",
            PortableContractKey.Scenario(PortableQueryScenario.CASE_INSENSITIVE),
            PortableVectorKind.BOUNDARY,
            predicate(
                NULLABLE_TEXT,
                PortableOperator.CONTAINS,
                string("alpha"),
                stringComparison = StringComparisonMode.CASE_INSENSITIVE
            ),
            ids("d08", "d10")
        ),
        vector(
            "starts-with-default-case-sensitive",
            PortableContractKey.Operator(PortableOperator.STARTS_WITH),
            PortableVectorKind.BOUNDARY,
            predicate(NULLABLE_TEXT, PortableOperator.STARTS_WITH, string("alpha")),
            ids("d10")
        ),
        vector(
            "starts-with-case-insensitive",
            PortableContractKey.Operator(PortableOperator.STARTS_WITH),
            PortableVectorKind.BOUNDARY,
            predicate(
                NULLABLE_TEXT,
                PortableOperator.STARTS_WITH,
                string("alpha"),
                stringComparison = StringComparisonMode.CASE_INSENSITIVE
            ),
            ids("d08", "d10")
        ),
        vector(
            "ends-with-default-case-sensitive",
            PortableContractKey.Operator(PortableOperator.ENDS_WITH),
            PortableVectorKind.BOUNDARY,
            predicate(NULLABLE_TEXT, PortableOperator.ENDS_WITH, string("alpha")),
            ids("d10")
        ),
        vector(
            "ends-with-case-insensitive",
            PortableContractKey.Operator(PortableOperator.ENDS_WITH),
            PortableVectorKind.BOUNDARY,
            predicate(
                NULLABLE_TEXT,
                PortableOperator.ENDS_WITH,
                string("alpha"),
                stringComparison = StringComparisonMode.CASE_INSENSITIVE
            ),
            ids("d08", "d10")
        ),
        vector(
            "enum-value",
            PortableContractKey.Scenario(PortableQueryScenario.ENUM_VALUE),
            PortableVectorKind.POSITIVE,
            predicate(STATUS, PortableOperator.EQ, QueryValue.EnumValue("PROCESSING")),
            ids("d03", "d06", "d09")
        ),
        vector(
            "instant-value",
            PortableContractKey.Scenario(PortableQueryScenario.INSTANT_VALUE),
            PortableVectorKind.POSITIVE,
            predicate(CREATED_AT, PortableOperator.BETWEEN, instant(3), instant(5)),
            ids("d03", "d04", "d05")
        ),
        vector(
            "element-match-same-element",
            PortableContractKey.Feature(QueryPortableFeature.ELEMENT_MATCH),
            PortableVectorKind.POSITIVE,
            elementMatch("A", 5),
            ids("d06", "d10")
        ),
        vector(
            "element-match-cross-element-rejected",
            PortableContractKey.Feature(QueryPortableFeature.ELEMENT_MATCH),
            PortableVectorKind.NEGATIVE,
            elementMatch("B", 1),
            ids()
        ),
        vector(
            "element-match-no-element",
            PortableContractKey.Feature(QueryPortableFeature.ELEMENT_MATCH),
            PortableVectorKind.BOUNDARY,
            elementMatch("Z", 5),
            ids()
        ),
        vector(
            "projection-logical-id",
            PortableContractKey.Scenario(PortableQueryScenario.PROJECTION),
            PortableVectorKind.POSITIVE,
            predicate(LOGICAL_ID, PortableOperator.EQ, string("d03")),
            ids("d03"),
            projection = QueryProjection.Include(setOf(LOGICAL_ID))
        ),
        vector(
            "stable-primary-sort-tie",
            PortableContractKey.Scenario(PortableQueryScenario.STABLE_TIE_SORT),
            PortableVectorKind.BOUNDARY,
            MatchAll,
            ids("d01", "d02", "d03", "d04", "d05", "d06", "d07", "d08", "d09", "d10"),
            sort = listOf(QuerySort(RANK, QuerySortDirection.ASC))
        ),
        vector(
            "single-empty",
            PortableContractKey.Scenario(PortableQueryScenario.EMPTY_SINGLE),
            PortableVectorKind.NEGATIVE,
            predicate(LOGICAL_ID, PortableOperator.EQ, string("missing")),
            ids()
        )
    )

    private fun logicalVectors(): List<PortableQueryVector> = listOf(
        vector(
            "logical-and",
            PortableContractKey.Logical(LogicalOperator.AND),
            PortableVectorKind.POSITIVE,
            logical(
                LogicalOperator.AND,
                predicate(ENABLED, PortableOperator.TRUE),
                predicate(STATUS, PortableOperator.EQ, QueryValue.EnumValue("NEW"))
            ),
            ids("d01", "d07")
        ),
        vector(
            "logical-or",
            PortableContractKey.Logical(LogicalOperator.OR),
            PortableVectorKind.POSITIVE,
            logical(
                LogicalOperator.OR,
                predicate(NULLABLE_TEXT, PortableOperator.NULL),
                predicate(STATUS, PortableOperator.EQ, QueryValue.EnumValue("PROCESSING"))
            ),
            ids("d02", "d03", "d06", "d09")
        ),
        vector(
            "logical-nor",
            PortableContractKey.Logical(LogicalOperator.NOR),
            PortableVectorKind.POSITIVE,
            logical(
                LogicalOperator.NOR,
                predicate(STATUS, PortableOperator.EQ, QueryValue.EnumValue("DONE")),
                predicate(ENABLED, PortableOperator.TRUE)
            ),
            ids("d04", "d06", "d10")
        )
    )

    private fun systemFieldVectors(): List<PortableQueryVector> = listOf(
        vector(
            "system-aggregate-id",
            PortableContractKey.SystemField(LogicalField("aggregateId")),
            PortableVectorKind.POSITIVE,
            predicate(LogicalField("aggregateId"), PortableOperator.EQ, string("d03")),
            ids("d03")
        ),
        vectorForKind(
            "system-event-record-id",
            PortableContractKey.SystemField(LogicalField("id")),
            PortableVectorKind.POSITIVE,
            predicate(LogicalField("id"), PortableOperator.EQ, string("event-d03")),
            QueryDocumentKind.EVENT_STREAM,
            ids("d03")
        ),
        vector(
            "system-tenant-id",
            PortableContractKey.SystemField(LogicalField("tenantId")),
            PortableVectorKind.POSITIVE,
            predicate(LogicalField("tenantId"), PortableOperator.EQ, string("tenant-2")),
            ids("d05", "d06", "d07")
        ),
        vector(
            "system-owner-id",
            PortableContractKey.SystemField(LogicalField("ownerId")),
            PortableVectorKind.POSITIVE,
            predicate(LogicalField("ownerId"), PortableOperator.EQ, string("owner-1")),
            ids("d01", "d02", "d05", "d08")
        ),
        vector(
            "system-space-id",
            PortableContractKey.SystemField(LogicalField("spaceId")),
            PortableVectorKind.POSITIVE,
            predicate(LogicalField("spaceId"), PortableOperator.EQ, string("space-1")),
            ids("d01", "d03", "d05", "d08")
        ),
        vectorForKind(
            "system-deleted-snapshot",
            PortableContractKey.SystemField(LogicalField("deleted")),
            PortableVectorKind.POSITIVE,
            MatchAll,
            QueryDocumentKind.SNAPSHOT,
            ids("d04", "d09"),
            requestedScope = RequestedQueryScope(deletion = DeletionScope.DELETED)
        )
    )

    private fun operationVectors(): List<PortableQueryVector> = listOf(
        vector(
            "operation-single",
            PortableContractKey.Operation(QueryOperation.SINGLE),
            PortableVectorKind.POSITIVE,
            predicate(LOGICAL_ID, PortableOperator.EQ, string("d03")),
            ids("d03"),
            sort = listOf(QuerySort(LOGICAL_ID, QuerySortDirection.ASC))
        ),
        vector(
            "operation-list",
            PortableContractKey.Operation(QueryOperation.LIST),
            PortableVectorKind.POSITIVE,
            predicate(LOGICAL_ID, PortableOperator.IN, string("d01"), string("d02"), string("d03")),
            ids("d01", "d02", "d03"),
            sort = listOf(QuerySort(LOGICAL_ID, QuerySortDirection.ASC))
        ),
        vector(
            "operation-page",
            PortableContractKey.Operation(QueryOperation.PAGE),
            PortableVectorKind.POSITIVE,
            predicate(LOGICAL_ID, PortableOperator.IN, string("d01"), string("d02"), string("d03")),
            ids("d01", "d02", "d03"),
            sort = listOf(QuerySort(LOGICAL_ID, QuerySortDirection.ASC))
        ),
        vector(
            "operation-count",
            PortableContractKey.Operation(QueryOperation.COUNT),
            PortableVectorKind.POSITIVE,
            predicate(LOGICAL_ID, PortableOperator.IN, string("d01"), string("d02"), string("d03")),
            ids("d01", "d02", "d03")
        )
    )

    private fun lifecycleVectors(): List<PortableQueryVector> = PortableLifecycleCase.entries.map { lifecycle ->
        vector(
            "lifecycle-${lifecycle.name.lowercase()}",
            PortableContractKey.Lifecycle(lifecycle),
            PortableVectorKind.BOUNDARY,
            MatchAll,
            ids("d01", "d02", "d03", "d04", "d05", "d06", "d07", "d08", "d09", "d10")
        )
    }

    private fun capabilityVectors(): List<PortableQueryVector> = listOf(
        vector(
            "unsupported-full-text",
            PortableContractKey.Capability(UNSUPPORTED_CAPABILITY),
            PortableVectorKind.BOUNDARY,
            FullTextExpression(UNSUPPORTED_CAPABILITY, "portable", setOf(TITLE)),
            ids(),
            error = QueryErrorCode.UNSUPPORTED_CAPABILITY
        )
    )

    private fun operatorCase(
        operator: PortableOperator,
        positive: Pair<QueryExpression, List<String>>,
        negative: Pair<QueryExpression, List<String>>,
        boundary: QueryExpression
    ): OperatorCase {
        val operatorId = operator.name.lowercase().replace('_', '-')
        return OperatorCase(
            listOf(
                vector(
                    "$operatorId-positive",
                    PortableContractKey.Operator(operator),
                    PortableVectorKind.POSITIVE,
                    positive.first,
                    positive.second
                ),
                vector(
                    "$operatorId-negative",
                    PortableContractKey.Operator(operator),
                    PortableVectorKind.NEGATIVE,
                    negative.first,
                    negative.second
                ),
                vector(
                    "$operatorId-boundary",
                    PortableContractKey.Operator(operator),
                    PortableVectorKind.BOUNDARY,
                    boundary,
                    ids(),
                    error = QueryErrorCode.INVALID_QUERY
                )
            )
        )
    }

    private fun vector(
        id: String,
        key: PortableContractKey,
        kind: PortableVectorKind,
        expression: QueryExpression,
        eventIds: List<String>,
        error: QueryErrorCode? = null,
        requestedScope: RequestedQueryScope = RequestedQueryScope(),
        sort: List<QuerySort> = emptyList(),
        projection: QueryProjection = QueryProjection.All
    ): PortableQueryVector = PortableQueryVector(
        id = id,
        key = key,
        kind = kind,
        expression = expression,
        expectations = mapOf(
            QueryDocumentKind.SNAPSHOT to expectation(eventIds.filterNot(DELETED_IDS::contains), error),
            QueryDocumentKind.EVENT_STREAM to expectation(eventIds, error)
        ),
        requestedScope = requestedScope,
        sort = sort,
        projection = projection
    )

    private fun vectorForKind(
        id: String,
        key: PortableContractKey,
        kind: PortableVectorKind,
        expression: QueryExpression,
        documentKind: QueryDocumentKind,
        expectedIds: List<String>,
        error: QueryErrorCode? = null,
        requestedScope: RequestedQueryScope = RequestedQueryScope()
    ): PortableQueryVector = PortableQueryVector(
        id = id,
        key = key,
        kind = kind,
        expression = expression,
        expectations = mapOf(documentKind to expectation(expectedIds, error)),
        requestedScope = requestedScope
    )

    private fun expectation(ids: List<String>, error: QueryErrorCode?): PortableQueryExpectation =
        PortableQueryExpectation(
            if (error == null) ids else emptyList(),
            if (error == null) ids.size.toLong() else 0,
            error
        )

    private fun predicate(
        field: LogicalField,
        operator: PortableOperator,
        vararg values: QueryValue,
        stringComparison: StringComparisonMode = StringComparisonMode.DEFAULT
    ): PredicateExpression = PredicateExpression(field, operator, values.toList(), stringComparison)

    private fun logical(operator: LogicalOperator, vararg expressions: PortableExpression): PortableLogicalExpression =
        PortableLogicalExpression(operator, expressions.toList())

    private fun elementMatch(sku: String, quantity: Long): ElementMatchExpression = ElementMatchExpression(
        ITEMS,
        logical(
            LogicalOperator.AND,
            predicate(LogicalField("sku"), PortableOperator.EQ, string(sku)),
            predicate(LogicalField("quantity"), PortableOperator.EQ, integer(quantity))
        )
    )

    private fun snapshotDocument(document: PortableQueryDocument): PortableStoredQueryDocument =
        PortableStoredQueryDocument(
            document.logicalId,
            QueryDocumentKind.SNAPSHOT,
            commonSystemFields(document) + document.fields + mapOf(
                LogicalField("aggregateId") to string(document.logicalId),
                LogicalField("eventId") to string("event-${document.logicalId}"),
                LogicalField("firstOperator") to string("operator"),
                LogicalField("operator") to string("operator"),
                LogicalField("firstEventTime") to instant(1),
                LogicalField("eventTime") to instant(10),
                LogicalField("snapshotTime") to instant(10),
                LogicalField("tags") to QueryValue.ObjectValue(emptyMap()),
                LogicalField("deleted") to QueryValue.BooleanValue(document.deleted),
                LogicalField("state") to QueryValue.ObjectValue(emptyMap())
            )
        )

    private fun eventStreamDocument(document: PortableQueryDocument): PortableStoredQueryDocument =
        PortableStoredQueryDocument(
            document.logicalId,
            QueryDocumentKind.EVENT_STREAM,
            commonSystemFields(document) + document.fields + mapOf(
                LogicalField("id") to string("event-${document.logicalId}"),
                LogicalField("aggregateId") to string(document.logicalId),
                LogicalField("commandId") to string("command-${document.logicalId}"),
                LogicalField("requestId") to string("request-${document.logicalId}"),
                LogicalField("createTime") to instant(10),
                LogicalField("header") to QueryValue.ObjectValue(emptyMap()),
                LogicalField("body") to QueryValue.ListValue(emptyList()),
                LogicalField("body.id") to string("body-${document.logicalId}"),
                LogicalField("body.name") to string("PortableEvent"),
                LogicalField("body.revision") to string("1"),
                LogicalField("body.bodyType") to string("DOMAIN_EVENT")
            )
        )

    private fun commonSystemFields(document: PortableQueryDocument): Map<LogicalField, QueryValue> = mapOf(
        LogicalField("contextName") to string(PORTABLE_AGGREGATE.contextName),
        LogicalField("aggregateName") to string(PORTABLE_AGGREGATE.aggregateName),
        LogicalField("tenantId") to string(document.tenantId),
        LogicalField("ownerId") to string(document.ownerId),
        LogicalField("spaceId") to string(document.spaceId),
        LogicalField("version") to integer(1),
        LOGICAL_ID to string(document.logicalId)
    )

    private fun document(
        id: String,
        scope: Triple<String, String, String>,
        deleted: Boolean = false,
        fields: Map<LogicalField, QueryValue>
    ): PortableQueryDocument = PortableQueryDocument(id, scope.first, scope.second, scope.third, deleted, fields)

    private fun fields(
        nullableText: QueryValue? = null,
        title: String,
        score: String,
        rank: Long,
        enabled: Boolean,
        status: String,
        day: Int,
        tags: List<String>,
        items: List<QueryValue.ObjectValue>,
        profile: QueryValue? = null
    ): Map<LogicalField, QueryValue> = LinkedHashMap<LogicalField, QueryValue>().apply {
        nullableText?.let { put(NULLABLE_TEXT, it) }
        put(TITLE, string(title))
        put(SCORE, decimal(score))
        put(RANK, integer(rank))
        put(ENABLED, QueryValue.BooleanValue(enabled))
        put(STATUS, QueryValue.EnumValue(status))
        put(CREATED_AT, instant(day))
        put(TAGS, QueryValue.ListValue(tags.map(::string)))
        put(ITEMS, QueryValue.ListValue(items))
        profile?.let { put(PROFILE, it) }
    }

    private fun item(sku: String, quantity: Long): QueryValue.ObjectValue = QueryValue.ObjectValue(
        mapOf("sku" to string(sku), "quantity" to integer(quantity))
    )

    private fun ids(vararg values: String): List<String> = values.toList()
    private fun string(value: String): QueryValue.StringValue = QueryValue.StringValue(value)
    private fun integer(value: Long): QueryValue.IntegerValue = QueryValue.IntegerValue(value)
    private fun decimal(value: String): QueryValue.DecimalValue = QueryValue.DecimalValue(BigDecimal(value))
    private fun instant(day: Int): QueryValue.InstantValue = QueryValue.InstantValue(
        Instant.parse("2026-01-${day.toString().padStart(2, '0')}T00:00:00Z")
    )

    private class OperatorCase(val vectors: List<PortableQueryVector>)

    private val USER_FIELDS: List<QueryFieldSchema> = listOf(
        QueryFieldSchema.string(LOGICAL_ID, nullable = false).copy(sortable = true),
        QueryFieldSchema.string(NULLABLE_TEXT, nullable = true),
        QueryFieldSchema.string(OPTIONAL_TEXT, nullable = true),
        QueryFieldSchema.string(TITLE, nullable = false).copy(
            capabilities = setOf(FULL_TEXT_CAPABILITY, UNSUPPORTED_CAPABILITY)
        ),
        QueryFieldSchema(SCORE, QueryFieldValueKind.DECIMAL, nullable = false),
        QueryFieldSchema(RANK, QueryFieldValueKind.INTEGER, nullable = false),
        QueryFieldSchema(ENABLED, QueryFieldValueKind.BOOLEAN, nullable = false),
        QueryFieldSchema(OPTIONAL_BOOLEAN, QueryFieldValueKind.BOOLEAN, nullable = true),
        QueryFieldSchema(STATUS, QueryFieldValueKind.ENUM, nullable = false),
        QueryFieldSchema(CREATED_AT, QueryFieldValueKind.TIME, nullable = false),
        QueryFieldSchema(
            TAGS,
            QueryFieldValueKind.STRING,
            nullable = false,
            collectionKind = QueryCollectionKind.SCALAR
        ),
        QueryFieldSchema(
            ITEMS,
            QueryFieldValueKind.OBJECT,
            nullable = false,
            collectionKind = QueryCollectionKind.OBJECT,
            elementMatchEnabled = true
        ),
        QueryFieldSchema.string(ITEM_SKU, nullable = false),
        QueryFieldSchema(ITEM_QUANTITY, QueryFieldValueKind.INTEGER, nullable = false),
        QueryFieldSchema(PROFILE, QueryFieldValueKind.OBJECT, nullable = true),
        QueryFieldSchema.string(PROFILE_CITY, nullable = false)
    )
}

private fun <T> immutableList(source: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(source))

private fun <K, V> immutableMap(source: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(source))
