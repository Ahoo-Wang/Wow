package me.ahoo.wow.mongo.query

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Sorts
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.mongo.query.MongoFilterConverter.toMongoFilter
import me.ahoo.wow.mongo.query.MongoFilterConverter.toMongoProjection
import me.ahoo.wow.mongo.query.MongoFilterConverter.toMongoSort
import org.bson.conversions.Bson
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class MongoFilterConverterTest {

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
        @JvmStatic
        fun toMongoFilterParameters(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(Condition.all().not(), Filters.not(Filters.empty())),
                Arguments.of(Condition.id("id"), Filters.eq("id")),
                Arguments.of(Condition.ids("id", "id2"), Filters.`in`(Documents.ID_FIELD, "id", "id2")),
                Arguments.of(Condition.eq("id", "id"), Filters.eq("id", "id")),
                Arguments.of(Condition.ne("id", "id"), Filters.ne("id", "id")),
                Arguments.of(Condition.gt("id", 1), Filters.gt("id", 1)),
                Arguments.of(Condition.lt("id", 1), Filters.lt("id", 1)),
                Arguments.of(Condition.gte("id", 1), Filters.gte("id", 1)),
                Arguments.of(Condition.lte("id", 1), Filters.lte("id", 1)),
                Arguments.of(Condition.contains("id", "value"), Filters.regex("id", "value")),
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
                Arguments.of(Condition.endsWith("id", "value"), Filters.regex("id", "value$")),
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
