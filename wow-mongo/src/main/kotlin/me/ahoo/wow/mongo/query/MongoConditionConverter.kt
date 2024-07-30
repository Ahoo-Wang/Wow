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

package me.ahoo.wow.mongo.query

import com.mongodb.client.model.Filters
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.query.converter.ConditionConverter
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import me.ahoo.wow.serialization.toJsonString
import org.bson.Document
import org.bson.conversions.Bson
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

object MongoConditionConverter : ConditionConverter<Bson> {
    override fun and(condition: Condition): Bson {
        require(condition.children.isNotEmpty()) {
            "AND operator children cannot be empty."
        }
        return Filters.and(condition.children.map { convert(it) })
    }

    override fun or(condition: Condition): Bson {
        require(condition.children.isNotEmpty()) {
            "OR operator children cannot be empty."
        }
        return Filters.or(condition.children.map { convert(it) })
    }

    override fun id(condition: Condition): Bson {
        return Filters.eq(condition.value)
    }

    override fun ids(condition: Condition): Bson {
        return Filters.`in`(Documents.ID_FIELD, condition.value as List<*>)
    }

    override fun tenantId(condition: Condition): Bson {
        return Filters.eq(MessageRecords.TENANT_ID, condition.value)
    }

    override fun all(condition: Condition): Bson = Filters.empty()

    override fun eq(condition: Condition): Bson {
        return Filters.eq(condition.field, condition.value)
    }

    override fun ne(condition: Condition): Bson {
        return Filters.ne(condition.field, condition.value)
    }

    override fun gt(condition: Condition): Bson {
        return Filters.gt(condition.field, condition.value)
    }

    override fun lt(condition: Condition): Bson {
        return Filters.lt(condition.field, condition.value)
    }

    override fun gte(condition: Condition): Bson {
        return Filters.gte(condition.field, condition.value)
    }

    override fun lte(condition: Condition): Bson {
        return Filters.lte(condition.field, condition.value)
    }

    override fun contains(condition: Condition): Bson {
        return Filters.regex(condition.field, condition.value as String)
    }

    override fun isIn(condition: Condition): Bson {
        return Filters.`in`(condition.field, condition.value as List<*>)
    }

    override fun notIn(condition: Condition): Bson {
        return Filters.nin(condition.field, condition.value as List<*>)
    }

    @Suppress("UNCHECKED_CAST")
    override fun between(condition: Condition): Bson {
        val valueIterable = condition.value as Iterable<Any>
        val ite = valueIterable.iterator()
        require(ite.hasNext()) {
            "BETWEEN operator value must be a array with 2 elements."
        }
        val first = ite.next()
        require(ite.hasNext()) {
            "BETWEEN operator value must be a array with 2 elements."
        }
        val second = ite.next()
        return Filters.and(Filters.gte(condition.field, first), Filters.lte(condition.field, second))
    }

    override fun allIn(condition: Condition): Bson {
        return Filters.all(condition.field, condition.value as List<*>)
    }

    override fun startsWith(condition: Condition): Bson {
        return Filters.regex(condition.field, "^${condition.value}")
    }

    override fun endsWith(condition: Condition): Bson {
        return Filters.regex(condition.field, "${condition.value}$")
    }

    override fun elemMatch(condition: Condition): Bson {
        return Filters.elemMatch(condition.field, condition.children.first().let { convert(it) })
    }

    override fun isNull(condition: Condition): Bson {
        return Filters.eq(condition.field, null)
    }

    override fun notNull(condition: Condition): Bson {
        return Filters.ne(condition.field, null)
    }

    override fun isTrue(condition: Condition): Bson {
        return Filters.eq(condition.field, true)
    }

    override fun isFalse(condition: Condition): Bson {
        return Filters.eq(condition.field, false)
    }

    override fun deleted(condition: Condition): Bson {
        return Filters.eq(StateAggregateRecords.DELETED, condition.value)
    }

    override fun today(condition: Condition): Bson {
        val startOfDay = LocalDateTime.now().with(LocalTime.MIN)
        val endOfDay = LocalDateTime.now().with(LocalTime.MAX)
        return Filters.and(
            Filters.gte(condition.field, startOfDay.toInstant(ZoneOffset.UTC).toEpochMilli()),
            Filters.lte(condition.field, endOfDay.toInstant(ZoneOffset.UTC).toEpochMilli())
        )
    }

    override fun tomorrow(condition: Condition): Bson {
        val startOfTomorrow = LocalDateTime.now().plusDays(1).with(LocalTime.MIN)
        val endOfTomorrow = LocalDateTime.now().plusDays(1).with(LocalTime.MAX)
        return Filters.and(
            Filters.gte(condition.field, startOfTomorrow.toInstant(ZoneOffset.UTC).toEpochMilli()),
            Filters.lte(condition.field, endOfTomorrow.toInstant(ZoneOffset.UTC).toEpochMilli())
        )
    }

    override fun thisWeek(condition: Condition): Bson {
        val startOfWeek =
            LocalDateTime.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).with(LocalTime.MIN)
        val endOfWeek = LocalDateTime.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).with(LocalTime.MAX)
        return Filters.and(
            Filters.gte(condition.field, startOfWeek.toInstant(ZoneOffset.UTC).toEpochMilli()),
            Filters.lte(condition.field, endOfWeek.toInstant(ZoneOffset.UTC).toEpochMilli())
        )
    }

    override fun nextWeek(condition: Condition): Bson {
        val startOfNextWeek = LocalDateTime.now().plusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .with(LocalTime.MIN)
        val endOfNextWeek =
            LocalDateTime.now().plusWeeks(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).with(LocalTime.MAX)
        return Filters.and(
            Filters.gte(condition.field, startOfNextWeek.toInstant(ZoneOffset.UTC).toEpochMilli()),
            Filters.lte(condition.field, endOfNextWeek.toInstant(ZoneOffset.UTC).toEpochMilli())
        )
    }

    override fun lastWeek(condition: Condition): Bson {
        val startOfLastWeek = LocalDateTime.now().minusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .with(LocalTime.MIN)
        val endOfLastWeek =
            LocalDateTime.now().minusWeeks(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).with(LocalTime.MAX)
        return Filters.and(
            Filters.gte(condition.field, startOfLastWeek.toInstant(ZoneOffset.UTC).toEpochMilli()),
            Filters.lte(condition.field, endOfLastWeek.toInstant(ZoneOffset.UTC).toEpochMilli())
        )
    }

    override fun thisMonth(condition: Condition): Bson {
        val startOfMonth = LocalDateTime.now().withDayOfMonth(1).with(LocalTime.MIN)
        val endOfMonth = LocalDateTime.now().with(TemporalAdjusters.lastDayOfMonth()).with(LocalTime.MAX)
        return Filters.and(
            Filters.gte(condition.field, startOfMonth.toInstant(ZoneOffset.UTC).toEpochMilli()),
            Filters.lte(condition.field, endOfMonth.toInstant(ZoneOffset.UTC).toEpochMilli())
        )
    }

    override fun lastMonth(condition: Condition): Bson {
        val startOfLastMonth = LocalDateTime.now().minusMonths(1).withDayOfMonth(1).with(LocalTime.MIN)
        val endOfLastMonth =
            LocalDateTime.now().minusMonths(1).with(TemporalAdjusters.lastDayOfMonth()).with(LocalTime.MAX)
        return Filters.and(
            Filters.gte(condition.field, startOfLastMonth.toInstant(ZoneOffset.UTC).toEpochMilli()),
            Filters.lte(condition.field, endOfLastMonth.toInstant(ZoneOffset.UTC).toEpochMilli())
        )
    }

    override fun recentDays(condition: Condition): Bson {
        val days = condition.value as Number
        val startOfRecentDays = LocalDateTime.now().minusDays(days.toLong() - 1).with(LocalTime.MIN)
        val endOfRecentDays = LocalDateTime.now().with(LocalTime.MAX)
        return Filters.and(
            Filters.gte(condition.field, startOfRecentDays.toInstant(ZoneOffset.UTC).toEpochMilli()),
            Filters.lte(condition.field, endOfRecentDays.toInstant(ZoneOffset.UTC).toEpochMilli())
        )
    }

    override fun raw(condition: Condition): Bson {
        return when (condition.value) {
            is Bson -> {
                condition.value as Bson
            }

            is String -> {
                Document.parse(condition.value as String)
            }

            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                Document(condition.value as Map<String, *>)
            }

            else -> {
                val conditionValueJson = condition.value.toJsonString()
                Document.parse(conditionValueJson)
            }
        }
    }

    override fun not(not: Boolean, target: Bson): Bson {
        if (!not) return target
        return Filters.not(target)
    }

    fun Condition.toMongoFilter(): Bson {
        return convert(this)
    }
}
