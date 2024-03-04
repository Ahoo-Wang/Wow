package me.ahoo.wow.mongo.query

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Sorts
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import org.bson.conversions.Bson

object MongoFilterConverter {

    /**
     * 将 Condition 转换成 Mongo DB 的 Filter Bson 对象
     */
    @Suppress("CyclomaticComplexMethod")
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
            Operator.TRUE -> Filters.eq(field, true)
            Operator.FALSE -> Filters.eq(field, false)
            Operator.BETWEEN -> {
                @Suppress("UNCHECKED_CAST")
                val valueIterable = value as Iterable<Any>
                val ite = valueIterable.iterator()
                require(ite.hasNext()) {
                    "BETWEEN operator value must be a array with 2 elements."
                }
                val first = ite.next()
                require(ite.hasNext()) {
                    "BETWEEN operator value must be a array with 2 elements."
                }
                val second = ite.next()
                Filters.and(Filters.gte(field, first), Filters.lte(field, second))
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
