package me.ahoo.wow.mongo.query

import com.mongodb.client.model.Filters
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.mongo.query.snapshot.SnapshotConditionConverter
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
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
    fun toMongoFilterBetweenError() {
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
    fun toMongoFilterAndError() {
        assertThrownBy<IllegalArgumentException> {
            Condition("", Operator.AND, "")
                .let {
                    SnapshotConditionConverter.convert(it)
                }
        }
    }

    @Test
    fun toMongoFilterOrError() {
        assertThrownBy<IllegalArgumentException> {
            Condition("", Operator.OR, "")
                .let {
                    SnapshotConditionConverter.convert(it)
                }
        }
    }

    @Test
    fun toMongoFilterNorError() {
        assertThrownBy<IllegalArgumentException> {
            Condition("", Operator.NOR, "")
                .let {
                    SnapshotConditionConverter.convert(it)
                }
        }
    }

    @Test
    fun today() {
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
    fun todayUTC() {
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
    fun beforeToday() {
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
    fun beforeTodayStringValue() {
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
    fun beforeTodayLongValue() {
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
    fun beforeTodayWrongValue() {
        assertThrownBy<IllegalArgumentException> {
            Condition.beforeToday("field", Any()).let {
                SnapshotConditionConverter.convert(it)
            }
        }
    }

    @Test
    fun beforeTodayDateTimeFormatter() {
        assertThrownBy<IllegalArgumentException> {
            Condition.beforeToday("field", 0, Any()).let {
                SnapshotConditionConverter.convert(it)
            }
        }
    }

    @Test
    fun tomorrow() {
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
    fun tomorrowDateTimeFormatter() {
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
    fun thisWeek() {
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
    fun nextWeek() {
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
    fun lastWeek() {
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
    fun thisMonth() {
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
    fun lastMonth() {
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
    fun recentDays() {
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
    fun earlierDays() {
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
    fun rawBson() {
        val expected = Filters.eq("id", "id")
        val actual = Condition.raw(expected).let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(actual, expected)
    }

    @Test
    fun rawString() {
        val actual = Condition.raw("{\"id\":\"id\"}").let {
            SnapshotConditionConverter.convert(it)
        }.toBsonDocument()
        val expected = Filters.eq("id", "id").toBsonDocument()
        assertConvert(actual, expected)
    }

    @Test
    fun rawMap() {
        val actual = Condition.raw(mapOf("id" to "id")).let {
            SnapshotConditionConverter.convert(it)
        }.toBsonDocument()

        val expected = Filters.eq("id", "id").toBsonDocument()
        assertConvert(actual, expected)
    }

    data class RawObj(val id: String)

    @Test
    fun rawObject() {
        val actual = Condition.raw(RawObj("id")).let {
            SnapshotConditionConverter.convert(it)
        }.toBsonDocument()
        val expected = Filters.eq("id", "id").toBsonDocument()
        assertConvert(actual, expected)
    }

    @ParameterizedTest
    @MethodSource("toMongoFilterParameters")
    fun toMongoFilter(condition: Condition, expected: Bson) {
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
                Arguments.of(
                    Condition.or(listOf(Condition.raw(Filters.eq("id", false)))),
                    Filters.or(Filters.eq("id", false))
                ),
                Arguments.of(
                    Condition.raw(Filters.eq("id", false)),
                    Filters.eq("id", false)
                ),
                Arguments.of(
                    Condition.raw("{id:false}"),
                    Filters.eq("id", false)
                )
            )
        }
    }
}
