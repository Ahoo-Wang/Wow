package me.ahoo.wow.mongo.query

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Sorts
import me.ahoo.wow.query.Condition
import me.ahoo.wow.query.Operator
import me.ahoo.wow.query.Projection
import me.ahoo.wow.query.Sort
import org.bson.conversions.Bson

object MongoFilterConverter {

    /**
     * 将 Condition 转换成 Mongo DB 的 Filter Bson 对象
     */
    @Suppress("UNCHECKED_CAST", "CyclomaticComplexMethod")
    fun Condition.toMongoFilter(): Bson {
        return when (operator) {
            Operator.EMPTY -> Filters.empty()
            Operator.EQ -> Filters.eq(field, value)
            Operator.NE -> Filters.ne(field, value)
            Operator.GT -> Filters.gt(field, value)
            Operator.LT -> Filters.lt(field, value)
            Operator.GTE -> Filters.gte(field, value)
            Operator.LTE -> Filters.lte(field, value)
            Operator.LIKE -> Filters.regex(field, value as String)
            Operator.IN -> Filters.`in`(field, value as List<*>)
            Operator.NOT_IN -> Filters.nin(field, value as List<*>)
            Operator.BETWEEN -> {
                val values = value as Array<Any>
                require(values.size == 2) {
                    "BETWEEN operator value must be a array with 2 elements."
                }
                Filters.and(Filters.gte(field, values[0]), Filters.lte(field, values[1]))
            }

            Operator.ALL -> Filters.all(field, value as List<*>)
            Operator.NULL -> Filters.eq(field, null)
            Operator.NOT_NULL -> Filters.ne(field, null)
            Operator.ELEM_MATCH -> Filters.elemMatch(field, children.first().toMongoFilter())
            Operator.STATS_WITH -> Filters.regex(field, "^$value")
            Operator.AND -> {
                require(children.isNotEmpty()) {
                    "AND operator children cannot be empty."
                }
                Filters.and(children.map { it.toMongoFilter() })
            }

            Operator.OR -> {
                require(children.isNotEmpty()) {
                    "OR operator children cannot be empty."
                }
                Filters.or(children.map { it.toMongoFilter() })
            }
        }
    }

    fun Projection.toMongoProjection(): Bson? {
        if (isEmpty()) return null
        if (include.isNotEmpty() && exclude.isNotEmpty()) {
            return Projections.fields(Projections.include(include), Projections.exclude(exclude))
        }
        if (include.isNotEmpty()) {
            return Projections.include(include)
        }
        return Projections.exclude(exclude)
    }

    fun List<Sort>.toMongoSort(): Bson? {
        if (isEmpty()) return null
        return map {
            when (it.direction) {
                Sort.Direction.ASC -> Sorts.ascending(it.field)
                Sort.Direction.DESC -> Sorts.descending(it.field)
            }
        }.toList().let {
            Sorts.orderBy(it)
        }
    }
}
