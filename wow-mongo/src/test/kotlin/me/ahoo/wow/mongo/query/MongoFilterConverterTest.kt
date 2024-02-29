package me.ahoo.wow.mongo.query

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Sorts
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.mongo.query.MongoFilterConverter.toMongoFilter
import me.ahoo.wow.mongo.query.MongoFilterConverter.toMongoProjection
import me.ahoo.wow.mongo.query.MongoFilterConverter.toMongoSort
import org.bson.conversions.Bson
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class MongoFilterConverterTest {

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
                Arguments.of(Condition.EMPTY, Filters.empty()),
                Arguments.of(Condition("id", Operator.EQ, "id"), Filters.eq("id", "id")),
                Arguments.of(Condition("id", Operator.NE, "id"), Filters.ne("id", "id")),
                Arguments.of(Condition("id", Operator.GT, 1), Filters.gt("id", 1)),
                Arguments.of(Condition("id", Operator.LT, 1), Filters.lt("id", 1)),
                Arguments.of(Condition("id", Operator.GTE, 1), Filters.gte("id", 1)),
                Arguments.of(Condition("id", Operator.LTE, 1), Filters.lte("id", 1)),
                Arguments.of(Condition("id", Operator.LIKE, "value"), Filters.regex("id", "value")),
                Arguments.of(Condition("id", Operator.IN, listOf("value")), Filters.`in`("id", listOf("value"))),
                Arguments.of(Condition("id", Operator.NOT_IN, listOf("value")), Filters.nin("id", listOf("value"))),
                Arguments.of(
                    Condition("id", Operator.BETWEEN, listOf(1, 2)),
                    Filters.and(Filters.gte("id", 1), Filters.lte("id", 2))
                ),
                Arguments.of(Condition("id", Operator.ALL, listOf("value")), Filters.all("id", listOf("value"))),
                Arguments.of(Condition("id", Operator.NULL, ""), Filters.eq("id", null)),
                Arguments.of(Condition("id", Operator.NOT_NULL, ""), Filters.ne("id", null)),
                Arguments.of(
                    Condition("id", Operator.ELEM_MATCH, children = listOf(Condition("id", Operator.EQ, "id"))),
                    Filters.elemMatch("id", Filters.eq("id", "id"))
                ),
                Arguments.of(Condition("id", Operator.STATS_WITH, "value"), Filters.regex("id", "^value")),
                Arguments.of(
                    Condition("", Operator.AND, children = listOf(Condition("id", Operator.EQ, "id"))),
                    Filters.and(Filters.eq("id", "id"))
                ),
                Arguments.of(
                    Condition("", Operator.OR, children = listOf(Condition("id", Operator.EQ, "id"))),
                    Filters.or(Filters.eq("id", "id"))
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
