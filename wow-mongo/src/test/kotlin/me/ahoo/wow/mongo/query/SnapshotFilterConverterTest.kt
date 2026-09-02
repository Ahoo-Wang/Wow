@file:Suppress("NoWildcardImports", "WildcardImport")

package me.ahoo.wow.mongo.query

import com.mongodb.client.model.Filters
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.*
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.mongo.query.snapshot.SnapshotFilterConverter
import me.ahoo.wow.query.dsl.filter
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

class SnapshotFilterConverterTest {

    private fun assertConvert(actual: Bson, expected: Bson) {
        val deletionBson = Filters.and(
            Filters.eq(StateAggregateRecords.DELETED, false),
            expected
        )
        actual.toBsonDocument().assert().isEqualTo(deletionBson.toBsonDocument())
    }

    @Test
    fun `snapshot metadata filters should target document and metadata fields`() {
        assertConvert(SnapshotFilterConverter.convert(IdFilter("id-1")), Filters.eq(Documents.ID_FIELD, "id-1"))
        assertConvert(
            SnapshotFilterConverter.convert(AggregateIdFilter("aggregate-1")),
            Filters.eq(Documents.ID_FIELD, "aggregate-1"),
        )
        assertConvert(
            SnapshotFilterConverter.convert(TenantIdFilter("tenant-1")),
            Filters.eq(MessageRecords.TENANT_ID, "tenant-1"),
        )
    }

    @Suppress("DEPRECATION")
    @Test
    fun `direct converter execution should accept a converted legacy condition`() {
        SnapshotFilterConverter.convert(Condition.id("id-1").toFilterExpression()).toBsonDocument().assert()
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
            SnapshotFilterConverter.convert(EqualFilter(QueryField("state.tags"), json(listOf("a", "b")))),
            Filters.eq("state.tags", listOf("a", "b")),
        )
        assertConvert(
            SnapshotFilterConverter.convert(
                NotEqualFilter(QueryField("state.tags"), json(listOf("a", "b"))),
            ),
            Filters.ne("state.tags", listOf("a", "b")),
        )
        assertConvert(
            SnapshotFilterConverter.convert(
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
            SnapshotFilterConverter.convert(Condition.isIn("timestamp", listOf(objectId)).toFilterExpression()),
            Filters.`in`("timestamp", objectId),
        )
        assertConvert(
            SnapshotFilterConverter.convert(Condition.notIn("timestamp", listOf(objectId)).toFilterExpression()),
            Filters.nin("timestamp", objectId),
        )
        assertConvert(
            SnapshotFilterConverter.convert(Condition.all("timestamp", listOf(objectId)).toFilterExpression()),
            Filters.all("timestamp", objectId),
        )
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy range predicates should preserve Date values`() {
        val lower = Date(1_000)
        val upper = Date(2_000)

        assertConvert(
            SnapshotFilterConverter.convert(Condition.gt("createdAt", lower).toFilterExpression()),
            Filters.gt("createdAt", lower),
        )
        assertConvert(
            SnapshotFilterConverter.convert(Condition.gte("createdAt", lower).toFilterExpression()),
            Filters.gte("createdAt", lower),
        )
        assertConvert(
            SnapshotFilterConverter.convert(Condition.lt("createdAt", upper).toFilterExpression()),
            Filters.lt("createdAt", upper),
        )
        assertConvert(
            SnapshotFilterConverter.convert(Condition.lte("createdAt", upper).toFilterExpression()),
            Filters.lte("createdAt", upper),
        )
        assertConvert(
            SnapshotFilterConverter.convert(Condition.between("createdAt", lower, upper).toFilterExpression()),
            Filters.and(Filters.gte("createdAt", lower), Filters.lte("createdAt", upper)),
        )
    }

    @Test
    fun `element predicate fields should remain relative`() {
        assertConvert(
            SnapshotFilterConverter.convert(
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
        val bson = SnapshotFilterConverter.convert(filter { "quantity" gt 1 }, "state.orders.lines")

        bson.toBsonDocument().toJson().assert().contains("state.orders.lines.quantity")
        SnapshotFilterConverter.convert(filter { "state.orders.lines.quantity" gt 1 }, "state.orders.lines")
            .toBsonDocument().toJson().assert()
            .contains("state.orders.lines.quantity")
            .doesNotContain("state.orders.lines.state.orders.lines.quantity")
        SnapshotFilterConverter.convert(filter { "state.orders.lines".exists() }, "state.orders.lines")
            .toBsonDocument().toJson().assert().contains("state.orders.lines")
    }

    @Test
    fun `element filter conversion should not add a default deletion scope`() {
        SnapshotFilterConverter.convertWithoutDefaultDeletion(MatchAllFilter)
            .toBsonDocument().assert().isEqualTo(Filters.empty().toBsonDocument())
    }

    @Test
    fun `scoped element predicate fields should remain relative`() {
        assertConvert(
            SnapshotFilterConverter.convert(
                ElementMatchFilter(QueryField("items"), EqualFilter(QueryField("quantity"), json(1))),
                "state.orders.lines",
            ),
            Filters.elemMatch("state.orders.lines.items", Filters.eq("quantity", 1)),
        )
    }

    @Test
    fun `explicit deletion filters should replace the default deletion scope`() {
        SnapshotFilterConverter.convert(
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
        SnapshotFilterConverter.convert(MatchNoneFilter).toBsonDocument().assert()
            .isEqualTo(org.bson.Document("\$expr", false).toBsonDocument())
    }

    @Test
    fun `mongo phrase search should reject embedded quotes`() {
        assertThrows<IllegalArgumentException> {
            SnapshotFilterConverter.convert(SearchFilter("event \"sourcing\"", mode = SearchMode.PHRASE))
        }
    }

    @ParameterizedTest
    @MethodSource("mongoFilterParameters")
    fun `should compile typed filter`(filter: FilterExpression, expected: Bson) {
        assertConvert(SnapshotFilterConverter.convert(filter), expected)
    }

    companion object {
        private fun json(value: Any?): JsonNode = JsonSerializer.valueToTree(value)

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
