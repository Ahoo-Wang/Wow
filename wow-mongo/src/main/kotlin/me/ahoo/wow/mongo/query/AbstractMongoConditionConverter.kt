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
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.query.converter.AbstractConditionConverter
import me.ahoo.wow.query.converter.FieldConverter
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import me.ahoo.wow.serialization.toJsonString
import org.bson.Document
import org.bson.conversions.Bson

abstract class AbstractMongoConditionConverter : AbstractConditionConverter<Bson>() {
    protected abstract val fieldConverter: FieldConverter
    protected open fun convertCondition(condition: Condition): Condition {
        val convertedField = fieldConverter.convert(condition.field)
        if (convertedField == condition.field) {
            return condition
        }
        return condition.copy(field = convertedField)
    }

    override fun convert(condition: Condition): Bson {
        val convertedCondition = convertCondition(condition)
        return super.convert(convertedCondition)
    }

    override fun and(condition: Condition): Bson {
        require(condition.children.isNotEmpty()) {
            "AND operator children cannot be empty."
        }
        return Filters.and(condition.children.map { internalConvert(it) })
    }

    override fun or(condition: Condition): Bson {
        require(condition.children.isNotEmpty()) {
            "OR operator children cannot be empty."
        }
        return Filters.or(condition.children.map { internalConvert(it) })
    }

    override fun nor(condition: Condition): Bson {
        require(condition.children.isNotEmpty()) {
            "NOR operator children cannot be empty."
        }
        return Filters.nor(condition.children.map { internalConvert(it) })
    }

    override fun id(condition: Condition): Bson {
        return Filters.eq(condition.value)
    }

    override fun ids(condition: Condition): Bson {
        return Filters.`in`(Documents.ID_FIELD, condition.valueAs<Iterable<String>>())
    }

    override fun tenantId(condition: Condition): Bson {
        return Filters.eq(MessageRecords.TENANT_ID, condition.value)
    }

    override fun ownerId(condition: Condition): Bson {
        return Filters.eq(MessageRecords.OWNER_ID, condition.value)
    }

    override fun spaceId(condition: Condition): Bson {
        return Filters.eq(MessageRecords.SPACE_ID, condition.value)
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

    /**
     * Escape special characters in regular expressions
     * @return Escaped string
     */
    private fun String.escapeRegex(): String {
        return replace("\\", "\\\\")
            .replace("^", "\\^")
            .replace("$", "\\$")
            .replace(".", "\\.")
            .replace("|", "\\|")
            .replace("?", "\\?")
            .replace("*", "\\*")
            .replace("+", "\\+")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("{", "\\{")
            .replace("}", "\\}")
    }

    private fun regex(field: String, value: String, ignoreCase: Boolean?): Bson {
        return if (ignoreCase == true) {
            Filters.regex(field, value, "i")
        } else {
            Filters.regex(field, value)
        }
    }

    override fun contains(condition: Condition): Bson {
        return regex(condition.field, condition.valueAs<String>().escapeRegex(), condition.ignoreCase())
    }

    override fun startsWith(condition: Condition): Bson {
        return regex(condition.field, "^${condition.valueAs<String>().escapeRegex()}", condition.ignoreCase())
    }

    override fun endsWith(condition: Condition): Bson {
        return regex(condition.field, "${condition.valueAs<String>().escapeRegex()}$", condition.ignoreCase())
    }

    override fun isIn(condition: Condition): Bson {
        return Filters.`in`(condition.field, condition.valueAs<Iterable<*>>())
    }

    override fun notIn(condition: Condition): Bson {
        return Filters.nin(condition.field, condition.valueAs<Iterable<*>>())
    }

    override fun between(condition: Condition): Bson {
        val valueIterable = condition.valueAs<Iterable<Any>>()
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
        return Filters.all(condition.field, condition.valueAs<Iterable<*>>())
    }

    override fun elemMatch(condition: Condition): Bson {
        return Filters.elemMatch(
            condition.field,
            condition.children.first().let { internalConvert(it) }
        )
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

    override fun exists(condition: Condition): Bson {
        return Filters.exists(condition.field, condition.valueAs())
    }

    override fun deleted(condition: Condition): Bson {
        return when (condition.deletionState()) {
            DeletionState.ACTIVE -> {
                Filters.eq(StateAggregateRecords.DELETED, false)
            }

            DeletionState.DELETED -> {
                Filters.eq(StateAggregateRecords.DELETED, true)
            }

            DeletionState.ALL -> {
                Filters.empty()
            }
        }
    }

    override fun raw(condition: Condition): Bson {
        return when (condition.value) {
            is Bson -> {
                condition.valueAs()
            }

            is String -> {
                Document.parse(condition.valueAs<String>())
            }

            is Map<*, *> -> {
                Document(condition.valueAs<Map<String, *>>())
            }

            else -> {
                val conditionValueJson = condition.value.toJsonString()
                Document.parse(conditionValueJson)
            }
        }
    }
}
