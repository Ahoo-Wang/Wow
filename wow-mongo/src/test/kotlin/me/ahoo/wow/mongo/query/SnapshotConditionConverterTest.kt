@file:Suppress("NoWildcardImports", "WildcardImport")

package me.ahoo.wow.mongo.query

import com.mongodb.client.model.Filters
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.query.*
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.mongo.query.snapshot.SnapshotConditionConverter
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import tools.jackson.databind.JsonNode
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.stream.Stream

class SnapshotConditionConverterTest {

    private fun assertConvert(actual: Bson, expected: Bson) {
        val deletionBson = Filters.and(
            Filters.eq(StateAggregateRecords.DELETED, false),
            expected
        )
        actual.toBsonDocument().assert().isEqualTo(deletionBson.toBsonDocument())
    }

    @Test
    fun `should convert filter expression`() {
        val actual = SnapshotConditionConverter.convert(
            me.ahoo.wow.api.query.EqualFilter(
                me.ahoo.wow.api.query.LogicalField("state.name"),
                me.ahoo.wow.serialization.JsonSerializer.valueToTree("Wow"),
            ),
        )

        assertConvert(actual, Filters.eq("state.name", "Wow"))
    }

    @Test
    fun `should compile filter expression operators`() {
        val field = LogicalField("state.value")
        val nestedField = LogicalField("name")
        val one = JsonSerializer.valueToTree<JsonNode>(1)
        val two = JsonSerializer.valueToTree<JsonNode>(2)
        val text = JsonSerializer.valueToTree<JsonNode>("value")
        val filters = listOf<FilterExpression>(
            MatchNoneFilter,
            AndFilter(listOf(EqualFilter(field, one), EqualFilter(field, two))),
            OrFilter(listOf(EqualFilter(field, one), EqualFilter(field, two))),
            NorFilter(listOf(EqualFilter(field, one), EqualFilter(field, two))),
            EqualFilter(field, JsonSerializer.valueToTree(true)),
            NotEqualFilter(field, one),
            GreaterThanFilter(field, one),
            GreaterThanOrEqualFilter(field, one),
            LessThanFilter(field, one),
            LessThanOrEqualFilter(field, one),
            ContainsFilter(field, "value.*", StringComparison.CASE_INSENSITIVE),
            StartsWithFilter(field, "value.*"),
            EndsWithFilter(field, "value.*"),
            InFilter(field, listOf(one, two)),
            NotInFilter(field, listOf(one, two)),
            BetweenFilter(field, one, two),
            ContainsAllFilter(field, listOf(one, two)),
            IsEmptyFilter(field),
            IsNullFilter(field),
            IsNotNullFilter(field),
            ExistsFilter(field),
            NotExistsFilter(field),
            ElementMatchFilter(field, EqualFilter(nestedField, text)),
            SearchFilter("value", linkedSetOf(field)),
            DeletionFilter(DeletionState.ACTIVE),
            DeletionFilter(DeletionState.DELETED),
            DeletionFilter(DeletionState.ALL),
        )

        filters.map { SnapshotConditionConverter.convert(it).toBsonDocument() }.assert().hasSize(filters.size)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `should preserve legacy document id and match semantics`() {
        val objectId = ObjectId()
        assertConvert(
            SnapshotConditionConverter.convert(Condition.id("aggregate-1").toFilterExpression()),
            Filters.eq(Documents.ID_FIELD, "aggregate-1"),
        )
        assertConvert(
            SnapshotConditionConverter.convert(Condition.match("state.name", "wow").toFilterExpression()),
            Filters.text("wow"),
        )
        assertConvert(
            SnapshotConditionConverter.convert(Condition.isNull("state.optional").toFilterExpression()),
            Filters.eq("state.optional", null),
        )
        assertConvert(
            SnapshotConditionConverter.convert(Condition.eq("state.tags", listOf("a", "b")).toFilterExpression()),
            Filters.eq("state.tags", listOf("a", "b")),
        )
        assertConvert(
            SnapshotConditionConverter.convert(Condition.ne("state.tags", listOf("a", "b")).toFilterExpression()),
            Filters.ne("state.tags", listOf("a", "b")),
        )
        assertConvert(
            SnapshotConditionConverter.convert(
                Condition.elemMatch(
                    "state.items",
                    Condition.eq(MessageRecords.AGGREGATE_ID, "nested-aggregate-id"),
                ).toFilterExpression(),
            ),
            Filters.elemMatch(
                "state.items",
                Filters.eq(MessageRecords.AGGREGATE_ID, "nested-aggregate-id"),
            ),
        )
        assertConvert(
            SnapshotConditionConverter.convert(Condition.eq("@timestamp", objectId).toFilterExpression()),
            Filters.eq("@timestamp", objectId),
        )
        assertConvert(
            SnapshotConditionConverter.convert(Condition.all("state.tags", emptyList()).toFilterExpression()),
            Filters.all("state.tags", emptyList<Any>()),
        )
    }

    @Test
    fun `should throw error when between filter has invalid values`() {
        assertThrownBy<IllegalArgumentException> {
            Condition("id", Operator.BETWEEN, listOf<Int>())
                .let {
                    SnapshotConditionConverter.convert(it)
                }
        }
        assertThrownBy<IllegalArgumentException> {
            Condition("id", Operator.BETWEEN, listOf(1))
                .let {
                    SnapshotConditionConverter.convert(it)
                }
        }
    }

    @Test
    fun `should throw error when and filter is empty`() {
        assertThrownBy<IllegalArgumentException> {
            Condition("", Operator.AND, "")
                .let {
                    SnapshotConditionConverter.convert(it)
                }
        }
    }

    @Test
    fun `should throw error when or filter is empty`() {
        assertThrownBy<IllegalArgumentException> {
            Condition("", Operator.OR, "")
                .let {
                    SnapshotConditionConverter.convert(it)
                }
        }
    }

    @Test
    fun `should throw error when nor filter is empty`() {
        assertThrownBy<IllegalArgumentException> {
            Condition("", Operator.NOR, "")
                .let {
                    SnapshotConditionConverter.convert(it)
                }
        }
    }

    @Test
    fun `should convert today condition`() {
        val actual = Condition.today("field").let {
            SnapshotConditionConverter.convert(it)
        }
        val expected = Filters.and(
            Filters.gte("field", OffsetDateTime.now().with(LocalTime.MIN).toInstant().toEpochMilli()),
            Filters.lte("field", OffsetDateTime.now().with(LocalTime.MAX).toInstant().toEpochMilli())
        )
        assertConvert(actual, expected)
    }

    @Test
    fun `should convert today condition with UTC timezone`() {
        val actual = Condition.today("field").copy(
            options = mapOf(
                Condition.ZONE_ID_OPTION_KEY to "UTC"
            )
        ).let {
            SnapshotConditionConverter.convert(it)
        }
        val expected = Filters.and(
            Filters.gte("field", OffsetDateTime.now(ZoneOffset.UTC).with(LocalTime.MIN).toInstant().toEpochMilli()),
            Filters.lte("field", OffsetDateTime.now(ZoneOffset.UTC).with(LocalTime.MAX).toInstant().toEpochMilli())
        )
        assertConvert(actual, expected)
    }

    @Test
    fun `should convert before today condition`() {
        val actual = Condition.beforeToday("field", LocalTime.NOON).let {
            SnapshotConditionConverter.convert(it)
        }
        val expected = Filters.lt(
            "field",
            OffsetDateTime.now().with(LocalTime.NOON).toInstant().toEpochMilli()
        )
        assertConvert(actual, expected)
    }

    @Test
    fun `should convert before today condition given string value`() {
        val actual = Condition.beforeToday("field", "12:00").let {
            SnapshotConditionConverter.convert(it)
        }
        val expected = Filters.lt(
            "field",
            OffsetDateTime.now().with(LocalTime.NOON).toInstant().toEpochMilli()
        )
        assertConvert(actual, expected)
    }

    @Test
    fun `should convert before today condition given long value`() {
        val actual = Condition.beforeToday("field", 0).let {
            SnapshotConditionConverter.convert(it)
        }
        val expected = Filters.lt(
            "field",
            OffsetDateTime.now().with(LocalTime.MIN).toInstant().toEpochMilli()
        )
        assertConvert(actual, expected)
    }

    @Test
    fun `should throw error when before today has wrong value type`() {
        assertThrownBy<IllegalArgumentException> {
            Condition.beforeToday("field", Any()).let {
                SnapshotConditionConverter.convert(it)
            }
        }
    }

    @Test
    fun `should throw error when before today has wrong formatter`() {
        assertThrownBy<IllegalArgumentException> {
            Condition.beforeToday("field", 0, Any()).let {
                SnapshotConditionConverter.convert(it)
            }
        }
    }

    @Test
    fun `should convert tomorrow condition`() {
        val actual = Condition.tomorrow("field").let {
            SnapshotConditionConverter.convert(it)
        }
        val expected = Filters.and(
            Filters.gte(
                "field",
                OffsetDateTime.now().plusDays(1).with(LocalTime.MIN).toInstant().toEpochMilli()
            ),
            Filters.lte(
                "field",
                OffsetDateTime.now().plusDays(1).with(LocalTime.MAX).toInstant().toEpochMilli()
            )
        )
        assertConvert(actual, expected)
    }

    @Test
    fun `should convert tomorrow condition with date time formatter`() {
        val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val actual = Condition.tomorrow("field", dateTimeFormatter).let {
            SnapshotConditionConverter.convert(it)
        }
        val expected = Filters.and(
            Filters.gte(
                "field",
                dateTimeFormatter.format(OffsetDateTime.now().plusDays(1).with(LocalTime.MIN))
            ),
            Filters.lte(
                "field",
                dateTimeFormatter.format(OffsetDateTime.now().plusDays(1).with(LocalTime.MAX))
            )
        )
        assertConvert(actual, expected)
    }

    @Test
    fun `should convert this week condition`() {
        val actual = Condition.thisWeek("field").let {
            SnapshotConditionConverter.convert(it)
        }
        val expected = Filters.and(
            Filters.gte(
                "field",
                OffsetDateTime.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).with(LocalTime.MIN)
                    .toInstant().toEpochMilli()
            ),
            Filters.lte(
                "field",
                OffsetDateTime.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).with(LocalTime.MAX)
                    .toInstant().toEpochMilli()
            )
        )
        assertConvert(actual, expected)
    }

    @Test
    fun `should convert next week condition`() {
        val actual = Condition.nextWeek("field").let {
            SnapshotConditionConverter.convert(it)
        }
        val expected = Filters.and(
            Filters.gte(
                "field",
                OffsetDateTime.now().plusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .with(LocalTime.MIN).toInstant().toEpochMilli()
            ),
            Filters.lte(
                "field",
                OffsetDateTime.now().plusWeeks(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                    .with(LocalTime.MAX).toInstant().toEpochMilli()
            )
        )
        assertConvert(actual, expected)
    }

    @Test
    fun `should convert last week condition`() {
        val actual = Condition.lastWeek("field").let {
            SnapshotConditionConverter.convert(it)
        }
        val expected = Filters.and(
            Filters.gte(
                "field",
                OffsetDateTime.now().minusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .with(LocalTime.MIN).toInstant().toEpochMilli()
            ),
            Filters.lte(
                "field",
                OffsetDateTime.now().minusWeeks(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                    .with(LocalTime.MAX).toInstant().toEpochMilli()
            )
        )
        assertConvert(actual, expected)
    }

    @Test
    fun `should convert this month condition`() {
        val actual = Condition.thisMonth("field").let {
            SnapshotConditionConverter.convert(it)
        }
        val expected = Filters.and(
            Filters.gte(
                "field",
                OffsetDateTime.now().withDayOfMonth(1).with(LocalTime.MIN).toInstant().toEpochMilli()
            ),
            Filters.lte(
                "field",
                OffsetDateTime.now().with(TemporalAdjusters.lastDayOfMonth()).with(LocalTime.MAX)
                    .toInstant().toEpochMilli()
            )
        )
        assertConvert(actual, expected)
    }

    @Test
    fun `should convert last month condition`() {
        val actual = Condition.lastMonth("field").let {
            SnapshotConditionConverter.convert(it)
        }
        val expected = Filters.and(
            Filters.gte(
                "field",
                OffsetDateTime.now().minusMonths(1).withDayOfMonth(1).with(LocalTime.MIN).toInstant()
                    .toEpochMilli()
            ),
            Filters.lte(
                "field",
                OffsetDateTime.now().minusMonths(1).with(TemporalAdjusters.lastDayOfMonth()).with(LocalTime.MAX)
                    .toInstant().toEpochMilli()
            )
        )
        assertConvert(actual, expected)
    }

    @Test
    fun `should convert recent days condition`() {
        val actual = Condition.recentDays("field", 2).let {
            SnapshotConditionConverter.convert(it)
        }
        val expected = Filters.and(
            Filters.gte(
                "field",
                OffsetDateTime.now().minusDays(1).with(LocalTime.MIN).toInstant().toEpochMilli()
            ),
            Filters.lte("field", OffsetDateTime.now().with(LocalTime.MAX).toInstant().toEpochMilli())
        )
        assertConvert(actual, expected)
    }

    @Test
    fun `should convert earlier days condition`() {
        val actual = Condition.earlierDays("field", 2).let {
            SnapshotConditionConverter.convert(it)
        }
        val expected = Filters.lt(
            "field",
            OffsetDateTime.now().minusDays(1).with(LocalTime.MIN).toInstant().toEpochMilli()
        )
        assertConvert(actual, expected)
    }

    @Test
    fun `should convert aggregate id in logical conditions`() {
        val condition = Condition.and(
            Condition.eq(MessageRecords.AGGREGATE_ID, "and"),
            Condition.or(Condition.eq(MessageRecords.AGGREGATE_ID, "or")),
            Condition.nor(Condition.eq(MessageRecords.AGGREGATE_ID, "nor")),
        )

        val actual = SnapshotConditionConverter.convert(condition)
        val expected = Filters.and(
            Filters.eq(Documents.ID_FIELD, "and"),
            Filters.or(Filters.eq(Documents.ID_FIELD, "or")),
            Filters.nor(Filters.eq(Documents.ID_FIELD, "nor")),
        )

        assertConvert(actual, expected)
    }

    @ParameterizedTest
    @MethodSource("toMongoFilterParameters")
    fun `should convert condition to mongo filter`(condition: Condition, expected: Bson) {
        val actual = condition.let {
            SnapshotConditionConverter.convert(it)
        }.toBsonDocument()
        if (condition.operator == Operator.DELETED) {
            actual.assert().isEqualTo(expected.toBsonDocument())
        } else {
            assertConvert(actual, expected)
        }
    }

    companion object {
        @Suppress("LongMethod")
        @JvmStatic
        fun toMongoFilterParameters(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(Condition.eq(MessageRecords.AGGREGATE_ID, "1"), Filters.eq(Documents.ID_FIELD, "1")),
                Arguments.of(Condition.deleted(DeletionState.ACTIVE), Filters.eq("deleted", false)),
                Arguments.of(Condition.tenantId("tenantId"), Filters.eq("tenantId", "tenantId")),
                Arguments.of(Condition.ownerId("ownerId"), Filters.eq("ownerId", "ownerId")),
                Arguments.of(Condition.nor(Condition.all()), Filters.nor(Filters.empty())),
                Arguments.of(Condition.id("id"), Filters.eq("id")),
                Arguments.of(Condition.aggregateId("id"), Filters.eq("id")),
                Arguments.of(Condition.ids("id", "id2"), Filters.`in`(Documents.ID_FIELD, "id", "id2")),
                Arguments.of(Condition.aggregateIds("id", "id2"), Filters.`in`(Documents.ID_FIELD, "id", "id2")),
                Arguments.of(Condition.eq("id", "id"), Filters.eq("id", "id")),
                Arguments.of(Condition.ne("id", "id"), Filters.ne("id", "id")),
                Arguments.of(Condition.gt("id", 1), Filters.gt("id", 1)),
                Arguments.of(Condition.lt("id", 1), Filters.lt("id", 1)),
                Arguments.of(Condition.gte("id", 1), Filters.gte("id", 1)),
                Arguments.of(Condition.lte("id", 1), Filters.lte("id", 1)),
                Arguments.of(Condition.contains("id", "value"), Filters.regex("id", "value")),
                Arguments.of(Condition.contains("id", "a+b"), Filters.regex("id", "a\\+b")),
                Arguments.of(Condition.contains("id", "value", true), Filters.regex("id", "value", "i")),
                Arguments.of(Condition.isIn("id", listOf("value")), Filters.`in`("id", listOf("value"))),
                Arguments.of(Condition.notIn("id", listOf("value")), Filters.nin("id", listOf("value"))),
                Arguments.of(
                    Condition.between("id", 1, 2),
                    Filters.and(Filters.gte("id", 1), Filters.lte("id", 2))
                ),
                Arguments.of(Condition.all("id", listOf("value")), Filters.all("id", listOf("value"))),
                Arguments.of(Condition.isNull("id"), Filters.eq("id", null)),
                Arguments.of(Condition.notNull("id"), Filters.ne("id", null)),
                Arguments.of(
                    Condition.elemMatch("id", Condition("id", Operator.EQ, "id")),
                    Filters.elemMatch("id", Filters.eq("id", "id"))
                ),
                Arguments.of(Condition.startsWith("id", "value"), Filters.regex("id", "^value")),
                Arguments.of(Condition.startsWith("id", "a+b"), Filters.regex("id", "^a\\+b")),
                Arguments.of(Condition.startsWith("id", "value", true), Filters.regex("id", "^value", "i")),
                Arguments.of(Condition.endsWith("id", "value"), Filters.regex("id", "value$")),
                Arguments.of(Condition.endsWith("id", "a+b"), Filters.regex("id", "a\\+b$")),
                Arguments.of(Condition.endsWith("id", "value", true), Filters.regex("id", "value$", "i")),
                Arguments.of(
                    Condition.and(listOf(Condition("id", Operator.EQ, "id"))),
                    Filters.and(Filters.eq("id", "id"))
                ),
                Arguments.of(
                    Condition.or(listOf(Condition("id", Operator.EQ, "id"))),
                    Filters.or(Filters.eq("id", "id"))
                ),
                Arguments.of(
                    Condition.or(listOf(Condition.isTrue("id"))),
                    Filters.or(Filters.eq("id", true))
                ),
                Arguments.of(
                    Condition.or(listOf(Condition.isFalse("id"))),
                    Filters.or(Filters.eq("id", false))
                ),
                Arguments.of(
                    Condition.or(listOf(Condition.exists("id"))),
                    Filters.or(Filters.exists("id", true))
                ),
            )
        }
    }
}
