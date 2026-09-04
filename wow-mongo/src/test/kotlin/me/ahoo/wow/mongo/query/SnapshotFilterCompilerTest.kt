@file:Suppress("NoWildcardImports", "WildcardImport")

package me.ahoo.wow.mongo.query

import com.mongodb.client.model.Filters
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.*
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.mongo.query.snapshot.SnapshotFilterCompiler
import me.ahoo.wow.query.dsl.filter
import me.ahoo.wow.query.schema.QueryFieldBinding
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryRewriteMode
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.schema.QueryStorageType
import me.ahoo.wow.query.schema.requireAccepted
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import tools.jackson.databind.JsonNode
import java.util.Date
import java.util.stream.Stream

class SnapshotFilterCompilerTest {
    private val schema = QueryModelSchema(
        model = QueryModel.SNAPSHOT,
        capabilities = emptySet(),
        fields = mapOf(
            binding(MessageRecords.AGGREGATE_ID, Documents.ID_FIELD, QueryCapability.EXACT_MATCH),
            binding("state.paymentStatus", "document.paymentStatus", QueryCapability.EXACT_MATCH),
            binding("state.orders", "document.orders", QueryCapability.ELEMENT_SCOPE),
            binding("state.orders.lines", "document.orders.lines", QueryCapability.ELEMENT_SCOPE),
            binding("state.orders.lines.sku", "document.orders.lines.code", QueryCapability.EXACT_MATCH),
        ),
    )

    private fun compile(filter: FilterExpression): Bson = SnapshotFilterCompiler.compile(filter, schema)

    private fun assertConvert(actual: Bson, expected: Bson) {
        val deletionBson = Filters.and(
            Filters.eq(StateAggregateRecords.DELETED, false),
            expected
        )
        actual.toBsonDocument().assert().isEqualTo(deletionBson.toBsonDocument())
    }

    @Test
    fun `snapshot metadata filters should target document and metadata fields`() {
        assertConvert(compile(IdFilter("id-1")), Filters.eq(Documents.ID_FIELD, "id-1"))
        assertConvert(
            compile(AggregateIdFilter("aggregate-1")),
            Filters.eq(Documents.ID_FIELD, "aggregate-1"),
        )
        assertConvert(
            compile(TenantIdFilter("tenant-1")),
            Filters.eq(MessageRecords.TENANT_ID, "tenant-1"),
        )
    }

    @Suppress("DEPRECATION")
    @Test
    fun `direct converter execution should accept a converted legacy condition`() {
        compile(Condition.id("id-1").toFilterExpression()).toBsonDocument().assert()
            .isEqualTo(
                Filters.and(
                    Filters.eq(StateAggregateRecords.DELETED, false),
                    Filters.eq(Documents.ID_FIELD, "id-1"),
                ).toBsonDocument(),
            )
    }

    @Suppress("DEPRECATION")
    @Test
    fun `equality filters should preserve scalar arrays and legacy ObjectId values`() {
        val objectId = ObjectId()
        assertConvert(
            compile(EqualFilter(QueryField("state.tags"), json(listOf("a", "b")))),
            Filters.eq("state.tags", listOf("a", "b")),
        )
        assertConvert(
            compile(
                NotEqualFilter(QueryField("state.tags"), json(listOf("a", "b"))),
            ),
            Filters.ne("state.tags", listOf("a", "b")),
        )
        assertConvert(
            compile(
                Condition.eq("timestamp", objectId).toFilterExpression(),
            ),
            Filters.eq("timestamp", objectId),
        )
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy collection predicates should preserve ObjectId values`() {
        val objectId = ObjectId()

        assertConvert(
            compile(Condition.isIn("timestamp", listOf(objectId)).toFilterExpression()),
            Filters.`in`("timestamp", objectId),
        )
        assertConvert(
            compile(Condition.notIn("timestamp", listOf(objectId)).toFilterExpression()),
            Filters.nin("timestamp", objectId),
        )
        assertConvert(
            compile(Condition.all("timestamp", listOf(objectId)).toFilterExpression()),
            Filters.all("timestamp", objectId),
        )
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy range predicates should preserve Date values`() {
        val lower = Date(1_000)
        val upper = Date(2_000)

        assertConvert(
            compile(Condition.gt("createdAt", lower).toFilterExpression()),
            Filters.gt("createdAt", lower),
        )
        assertConvert(
            compile(Condition.gte("createdAt", lower).toFilterExpression()),
            Filters.gte("createdAt", lower),
        )
        assertConvert(
            compile(Condition.lt("createdAt", upper).toFilterExpression()),
            Filters.lt("createdAt", upper),
        )
        assertConvert(
            compile(Condition.lte("createdAt", upper).toFilterExpression()),
            Filters.lte("createdAt", upper),
        )
        assertConvert(
            compile(Condition.between("createdAt", lower, upper).toFilterExpression()),
            Filters.and(Filters.gte("createdAt", lower), Filters.lte("createdAt", upper)),
        )
    }

    @Test
    fun `element predicate fields should remain relative`() {
        assertConvert(
            compile(
                ElementMatchFilter(
                    QueryField("state.items"),
                    EqualFilter(QueryField(MessageRecords.AGGREGATE_ID), json("nested-aggregate-id")),
                ),
            ),
            Filters.elemMatch(
                "state.items",
                Filters.eq(MessageRecords.AGGREGATE_ID, "nested-aggregate-id"),
            ),
        )
    }

    @Test
    fun `scoped filter fields should be prefixed with parent`() {
        val parent = QueryField("state.orders.lines")
        val bson = SnapshotFilterCompiler.compileWithoutDefaultDeletion(
            filter { "quantity" gt 1 },
            schema,
            logicalParent = parent,
            resolvedParent = parent,
            physicalParent = parent,
        )

        bson.toBsonDocument().toJson().assert().contains("state.orders.lines.quantity")
        SnapshotFilterCompiler.compileWithoutDefaultDeletion(
            filter { "state.orders.lines.quantity" gt 1 },
            schema,
            logicalParent = parent,
            resolvedParent = parent,
            physicalParent = parent,
        )
            .toBsonDocument().toJson().assert()
            .contains("state.orders.lines.quantity")
            .doesNotContain("state.orders.lines.state.orders.lines.quantity")
        SnapshotFilterCompiler.compileWithoutDefaultDeletion(
            filter { "state.orders.lines".exists() },
            schema,
            logicalParent = parent,
            resolvedParent = parent,
            physicalParent = parent,
        )
            .toBsonDocument().toJson().assert().contains("state.orders.lines")
    }

    @Test
    fun `element filter conversion should not add a default deletion scope`() {
        SnapshotFilterCompiler.compileWithoutDefaultDeletion(MatchAllFilter, schema)
            .toBsonDocument().assert().isEqualTo(Filters.empty().toBsonDocument())
    }

    @Test
    fun `scoped element predicate fields should remain relative`() {
        SnapshotFilterCompiler.compileWithoutDefaultDeletion(
            ElementMatchFilter(QueryField("items"), EqualFilter(QueryField("quantity"), json(1))),
            schema,
            logicalParent = QueryField("state.orders.lines"),
            resolvedParent = QueryField("state.orders.lines"),
            physicalParent = QueryField("state.orders.lines"),
        ).toBsonDocument().assert().isEqualTo(
            Filters.elemMatch("state.orders.lines.items", Filters.eq("quantity", 1)).toBsonDocument(),
        )
    }

    @Test
    fun `explicit deletion filters should replace the default deletion scope`() {
        compile(
            AndFilter(
                listOf(
                    DeletionFilter(DeletionState.DELETED),
                    EqualFilter(QueryField("state.name"), json("Wow")),
                ),
            ),
        ).toBsonDocument().assert().isEqualTo(
            Filters.and(
                Filters.eq(StateAggregateRecords.DELETED, true),
                Filters.eq("state.name", "Wow"),
            ).toBsonDocument(),
        )
    }

    @Test
    fun `match none should absorb the default deletion scope`() {
        compile(MatchNoneFilter).toBsonDocument().assert()
            .isEqualTo(org.bson.Document("\$expr", false).toBsonDocument())
    }

    @Test
    fun `mongo phrase search should reject embedded quotes`() {
        assertThrows<IllegalArgumentException> {
            compile(SearchFilter("event \"sourcing\"", mode = SearchMode.PHRASE))
        }
    }

    @ParameterizedTest
    @MethodSource("mongoFilterParameters")
    fun `should compile typed filter`(filter: FilterExpression, expected: Bson) {
        assertConvert(compile(filter), expected)
    }

    @Test
    fun `exact binding and compatible fields should use physical and original paths`() {
        assertConvert(
            compile(EqualFilter(QueryField("state.paymentStatus"), json("PAID"))),
            Filters.eq("document.paymentStatus", "PAID"),
        )
        assertConvert(
            compile(EqualFilter(QueryField("state.dynamic"), json("value"))),
            Filters.eq("state.dynamic", "value")
        )
    }

    @Test
    fun `resolved filter field should compile to its physical binding`() {
        val mappedSchema = QueryModelSchema(
            model = QueryModel.SNAPSHOT,
            capabilities = emptySet(),
            fields = mapOf(
                binding(
                    logicalPath = "state.name",
                    physicalPath = "storage.name",
                    capability = QueryCapability.EXACT_MATCH,
                    resolvedPath = "document.name",
                    rewriteMode = QueryRewriteMode.REQUIRED,
                ),
            ),
        )
        val resolved = mappedSchema.resolve(
            EqualFilter(QueryField("state.name"), json("Wow")),
        ).requireAccepted(QuerySchemaValidationMode.STRICT)

        assertConvert(
            SnapshotFilterCompiler.compile(resolved, mappedSchema),
            Filters.eq("storage.name", "Wow"),
        )
    }

    @Test
    fun `nested element predicates should use relative physical paths once`() {
        assertConvert(
            compile(
                ElementMatchFilter(
                    QueryField("state.orders"),
                    ElementMatchFilter(QueryField("lines"), EqualFilter(QueryField("sku"), json("sku-1"))),
                ),
            ),
            Filters.elemMatch(
                "document.orders",
                Filters.elemMatch("lines", Filters.eq("code", "sku-1")),
            ),
        )
    }

    @Test
    fun `resolved dynamic element child should compile to its relative physical binding`() {
        val mappedSchema = QueryModelSchema(
            model = QueryModel.SNAPSHOT,
            capabilities = emptySet(),
            fields = mapOf(
                binding(
                    logicalPath = "state.orders",
                    physicalPath = "storage.orders",
                    capability = QueryCapability.ELEMENT_SCOPE,
                    resolvedPath = "document.orders",
                    rewriteMode = QueryRewriteMode.REQUIRED,
                ),
                binding(
                    logicalPath = "state.orders.attributes",
                    physicalPath = "storage.orders.values",
                    capability = QueryCapability.EXACT_MATCH,
                    resolvedPath = "document.orders.properties",
                    rewriteMode = QueryRewriteMode.REQUIRED,
                    dynamicChildren = true,
                ),
            ),
        )
        val resolved = mappedSchema.resolve(
            ElementMatchFilter(
                QueryField("state.orders"),
                EqualFilter(QueryField("state.orders.attributes.color"), json("blue")),
            ),
        ).requireAccepted(QuerySchemaValidationMode.STRICT)

        assertConvert(
            SnapshotFilterCompiler.compile(resolved, mappedSchema),
            Filters.elemMatch("storage.orders", Filters.eq("values.color", "blue")),
        )
    }

    @Test
    fun `element binding should be preserved when its physical path equals the absolute logical path`() {
        val mappedSchema = QueryModelSchema(
            model = QueryModel.SNAPSHOT,
            capabilities = emptySet(),
            fields = mapOf(
                binding(
                    logicalPath = "orders",
                    physicalPath = "storage",
                    capability = QueryCapability.ELEMENT_SCOPE,
                    cardinality = QueryCardinality.MANY,
                    valueTypes = setOf(QueryValueType.OBJECT),
                ),
                binding(
                    logicalPath = "orders.price",
                    physicalPath = "storage.orders.price",
                    capability = QueryCapability.EXACT_MATCH,
                    valueTypes = setOf(QueryValueType.INTEGER),
                ),
            ),
        )
        val resolved = mappedSchema.resolve(
            ElementMatchFilter(
                QueryField("orders"),
                EqualFilter(QueryField("orders.price"), json(10)),
            ),
        ).requireAccepted(QuerySchemaValidationMode.STRICT)

        assertConvert(
            SnapshotFilterCompiler.compile(resolved, mappedSchema),
            Filters.elemMatch("storage", Filters.eq("orders.price", 10)),
        )
    }

    @Test
    fun `compatible absolute element predicate fields should remain relative`() {
        val mappedSchema = QueryModelSchema(
            model = QueryModel.SNAPSHOT,
            capabilities = emptySet(),
            fields = mapOf(
                binding(
                    logicalPath = "state.orders",
                    physicalPath = "storage.orders",
                    capability = QueryCapability.ELEMENT_SCOPE,
                    resolvedPath = "document.orders",
                    rewriteMode = QueryRewriteMode.REQUIRED,
                ),
            ),
        )
        val resolved = mappedSchema.resolve(
            ElementMatchFilter(
                QueryField("state.orders"),
                EqualFilter(QueryField("state.orders.unknown"), json("value")),
            ),
        ).requireAccepted(QuerySchemaValidationMode.COMPATIBLE)

        assertConvert(
            SnapshotFilterCompiler.compile(resolved, mappedSchema),
            Filters.elemMatch(
                "storage.orders",
                Filters.eq("unknown", "value"),
            ),
        )
    }

    companion object {
        private fun json(value: Any?): JsonNode = JsonSerializer.valueToTree(value)

        private fun binding(
            logicalPath: String,
            physicalPath: String,
            capability: QueryCapability,
            resolvedPath: String = logicalPath,
            rewriteMode: QueryRewriteMode = QueryRewriteMode.NONE,
            dynamicChildren: Boolean = false,
            cardinality: QueryCardinality = QueryCardinality.SINGLE,
            valueTypes: Set<QueryValueType> = setOf(QueryValueType.STRING),
        ): Pair<QueryField, QueryFieldSchema> {
            val logical = QueryField(logicalPath)
            return logical to QueryFieldSchema(
                title = null,
                description = null,
                enumValues = null,
                valueTypes = valueTypes,
                nullable = false,
                required = true,
                cardinality = cardinality,
                semanticType = null,
                dynamicChildren = dynamicChildren,
                bindings = mapOf(
                    capability to QueryFieldBinding(
                        QueryField(resolvedPath),
                        QueryField(physicalPath),
                        QueryStorageType("test"),
                    ),
                ),
                rewriteMode = rewriteMode,
            )
        }

        @JvmStatic
        fun mongoFilterParameters(): Stream<Arguments> {
            val field = QueryField("state.value")
            val nestedField = QueryField("name")
            val one = json(1)
            val two = json(2)
            val text = json("value")
            return Stream.of(
                Arguments.of(IdsFilter(listOf("id-1", "id-2")), Filters.`in`(Documents.ID_FIELD, "id-1", "id-2")),
                Arguments.of(
                    AggregateIdsFilter(listOf("aggregate-1", "aggregate-2")),
                    Filters.`in`(Documents.ID_FIELD, "aggregate-1", "aggregate-2"),
                ),
                Arguments.of(OwnerIdFilter("owner-1"), Filters.eq(MessageRecords.OWNER_ID, "owner-1")),
                Arguments.of(SpaceIdFilter("space-1"), Filters.eq(MessageRecords.SPACE_ID, "space-1")),
                Arguments.of(
                    OrFilter(listOf(EqualFilter(field, one), EqualFilter(field, two))),
                    Filters.or(Filters.eq("state.value", 1), Filters.eq("state.value", 2)),
                ),
                Arguments.of(NorFilter(listOf(EqualFilter(field, one))), Filters.nor(Filters.eq("state.value", 1))),
                Arguments.of(EqualFilter(field, json(true)), Filters.eq("state.value", true)),
                Arguments.of(NotEqualFilter(field, one), Filters.ne("state.value", 1)),
                Arguments.of(GreaterThanFilter(field, one), Filters.gt("state.value", 1)),
                Arguments.of(GreaterThanOrEqualFilter(field, one), Filters.gte("state.value", 1)),
                Arguments.of(LessThanFilter(field, one), Filters.lt("state.value", 1)),
                Arguments.of(LessThanOrEqualFilter(field, one), Filters.lte("state.value", 1)),
                Arguments.of(
                    ContainsFilter(field, "value.*", StringComparison.CASE_INSENSITIVE),
                    Filters.regex("state.value", "value\\.\\*", "i"),
                ),
                Arguments.of(StartsWithFilter(field, "value.*"), Filters.regex("state.value", "^value\\.\\*")),
                Arguments.of(EndsWithFilter(field, "value.*"), Filters.regex("state.value", "value\\.\\*$")),
                Arguments.of(InFilter(field, listOf(one, two)), Filters.`in`("state.value", 1, 2)),
                Arguments.of(NotInFilter(field, listOf(one, two)), Filters.nin("state.value", 1, 2)),
                Arguments.of(
                    BetweenFilter(field, one, two),
                    Filters.and(Filters.gte("state.value", 1), Filters.lte("state.value", 2)),
                ),
                Arguments.of(ContainsAllFilter(field, listOf(one, two)), Filters.all("state.value", 1, 2)),
                Arguments.of(IsEmptyFilter(field), Filters.size("state.value", 0)),
                Arguments.of(IsNullFilter(field), Filters.eq("state.value", null)),
                Arguments.of(IsNotNullFilter(field), Filters.ne("state.value", null)),
                Arguments.of(ExistsFilter(field), Filters.exists("state.value")),
                Arguments.of(NotExistsFilter(field), Filters.exists("state.value", false)),
                Arguments.of(
                    ElementMatchFilter(field, EqualFilter(nestedField, text)),
                    Filters.elemMatch("state.value", Filters.eq("name", "value")),
                ),
                Arguments.of(SearchFilter("value", linkedSetOf(field)), Filters.text("value")),
                Arguments.of(
                    SearchFilter("event sourcing", mode = SearchMode.PHRASE),
                    Filters.text("\"event sourcing\""),
                ),
            )
        }
    }
}
