package me.ahoo.wow.mongo.query

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Sorts
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.mongo.query.MongoConditionConverter.toMongoFilter
import me.ahoo.wow.mongo.query.MongoProjectionConverter.toMongoProjection
import me.ahoo.wow.mongo.query.MongoSortConverter.toMongoSort
import org.bson.conversions.Bson
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.stream.Stream

class MongoConverterTest {

    @Test
    fun toMongoFilterBetweenError() {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            Condition("id", Operator.BETWEEN, listOf<Int>())
                .toMongoFilter()
        }
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            Condition("id", Operator.BETWEEN, listOf(1))
                .toMongoFilter()
        }
    }

    @Test
    fun toMongoFilterAndError() {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            Condition("", Operator.AND, "")
                .toMongoFilter()
        }
    }

    @Test
    fun toMongoFilterOrError() {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            Condition("", Operator.OR, "")
                .toMongoFilter()
        }
    }

    @Test
    fun toMongoFilterNorError() {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            Condition("", Operator.NOR, "")
                .toMongoFilter()
        }
    }

    @Test
    fun today() {
        val actual = Condition.today("field").toMongoFilter()
        val expected = Filters.and(
            Filters.gte("field", OffsetDateTime.now().with(LocalTime.MIN).toInstant().toEpochMilli()),
            Filters.lte("field", OffsetDateTime.now().with(LocalTime.MAX).toInstant().toEpochMilli())
        )
        assertThat(actual, equalTo(expected))
    }

    @Test
    fun todayUTC() {
        val actual = Condition.today("field").copy(
            options = mapOf(
                Condition.ZONE_ID_OPTION_KEY to "UTC"
            )
        ).toMongoFilter()
        val expected = Filters.and(
            Filters.gte("field", OffsetDateTime.now(ZoneOffset.UTC).with(LocalTime.MIN).toInstant().toEpochMilli()),
            Filters.lte("field", OffsetDateTime.now(ZoneOffset.UTC).with(LocalTime.MAX).toInstant().toEpochMilli())
        )
        assertThat(actual, equalTo(expected))
    }

    @Test
    fun beforeToday() {
        val actual = Condition.beforeToday("field", LocalTime.NOON).toMongoFilter()
        val expected = Filters.lt(
            "field",
            OffsetDateTime.now().with(LocalTime.NOON).toInstant().toEpochMilli()
        )
        assertThat(actual, equalTo(expected))
    }

    @Test
    fun beforeTodayStringValue() {
        val actual = Condition.beforeToday("field", "12:00").toMongoFilter()
        val expected = Filters.lt(
            "field",
            OffsetDateTime.now().with(LocalTime.NOON).toInstant().toEpochMilli()
        )
        assertThat(actual, equalTo(expected))
    }

    @Test
    fun beforeTodayWrongValue() {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            Condition.beforeToday("field", Any()).toMongoFilter()
        }
    }

    @Test
    fun tomorrow() {
        val actual = Condition.tomorrow("field").toMongoFilter()
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
        assertThat(actual, equalTo(expected))
    }

    @Test
    fun thisWeek() {
        val actual = Condition.thisWeek("field").toMongoFilter()
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
        assertThat(actual, equalTo(expected))
    }

    @Test
    fun nextWeek() {
        val actual = Condition.nextWeek("field").toMongoFilter()
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
        assertThat(actual, equalTo(expected))
    }

    @Test
    fun lastWeek() {
        val actual = Condition.lastWeek("field").toMongoFilter()
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
        assertThat(actual, equalTo(expected))
    }

    @Test
    fun thisMonth() {
        val actual = Condition.thisMonth("field").toMongoFilter()
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
        assertThat(actual, equalTo(expected))
    }

    @Test
    fun lastMonth() {
        val actual = Condition.lastMonth("field").toMongoFilter()
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
        assertThat(actual, equalTo(expected))
    }

    @Test
    fun recentDays() {
        val actual = Condition.recentDays("field", 2).toMongoFilter()
        val expected = Filters.and(
            Filters.gte(
                "field",
                OffsetDateTime.now().minusDays(1).with(LocalTime.MIN).toInstant().toEpochMilli()
            ),
            Filters.lte("field", OffsetDateTime.now().with(LocalTime.MAX).toInstant().toEpochMilli())
        )
        assertThat(actual, equalTo(expected))
    }

    @Test
    fun rawBson() {
        val expected = Filters.eq("id", "id")
        val actual = Condition.raw(expected).toMongoFilter()
        assertThat(actual, equalTo(expected))
    }

    @Test
    fun rawString() {
        val actual = Condition.raw("{\"id\":\"id\"}").toMongoFilter().toBsonDocument()
        val expected = Filters.eq("id", "id").toBsonDocument()
        assertThat(actual, equalTo(expected))
    }

    @Test
    fun rawMap() {
        val actual = Condition.raw(mapOf("id" to "id")).toMongoFilter().toBsonDocument()
        val expected = Filters.eq("id", "id").toBsonDocument()
        assertThat(actual, equalTo(expected))
    }

    data class RawObj(val id: String)

    @Test
    fun rawObject() {
        val actual = Condition.raw(RawObj("id")).toMongoFilter().toBsonDocument()
        val expected = Filters.eq("id", "id").toBsonDocument()
        assertThat(actual, equalTo(expected))
    }

    @ParameterizedTest
    @MethodSource("toMongoFilterParameters")
    fun toMongoFilter(condition: Condition, expected: Bson) {
        val actual = condition.toMongoFilter().toBsonDocument()
        assertThat(actual, equalTo(expected.toBsonDocument()))
    }

    @ParameterizedTest
    @MethodSource("toMongoProjectionParameters")
    fun toMongoProjection(projection: Projection, expected: Bson?) {
        val actual = projection.toMongoProjection()
        assertThat(actual, equalTo(expected))
    }

    @ParameterizedTest
    @MethodSource("toMongoSortParameters")
    fun toMongoSort(sort: List<Sort>, expected: Bson?) {
        val actual = sort.toMongoSort()
        assertThat(actual, equalTo(expected))
    }

    companion object {
        @Suppress("LongMethod")
        @JvmStatic
        fun toMongoFilterParameters(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(Condition.deleted(false), Filters.eq("deleted", false)),
                Arguments.of(Condition.tenantId("tenantId"), Filters.eq("tenantId", "tenantId")),
                Arguments.of(Condition.nor(Condition.all()), Filters.nor(Filters.empty())),
                Arguments.of(Condition.id("id"), Filters.eq("id")),
                Arguments.of(Condition.ids("id", "id2"), Filters.`in`(Documents.ID_FIELD, "id", "id2")),
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

        @JvmStatic
        fun toMongoProjectionParameters(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(Projection.ALL, null),
                Arguments.of(Projection(include = listOf("include")), Projections.include(listOf("include"))),
                Arguments.of(Projection(exclude = listOf("exclude")), Projections.exclude(listOf("exclude"))),
                Arguments.of(
                    Projection(include = listOf("include"), exclude = listOf("exclude")),
                    Projections.fields(Projections.include(listOf("include")), Projections.exclude(listOf("exclude")))
                ),
            )
        }

        @JvmStatic
        fun toMongoSortParameters(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(emptyList<Sort>(), null),
                Arguments.of(
                    listOf(Sort("fieldA", Sort.Direction.ASC)),
                    Sorts.orderBy(Sorts.ascending("fieldA"))
                ),
                Arguments.of(
                    listOf(Sort("fieldA", Sort.Direction.ASC), Sort("fieldD", Sort.Direction.DESC)),
                    Sorts.orderBy(Sorts.ascending("fieldA"), Sorts.descending("fieldD"))
                ),
            )
        }
    }
}
