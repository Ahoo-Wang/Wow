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

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.json.JsonData
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.query.converter.ConditionConverter
import me.ahoo.wow.serialization.MessageRecords

object ElasticsearchConditionConverter : ConditionConverter<Query> {
    override fun and(condition: Condition): Query {
        return Query.Builder().bool { builder ->
            builder.filter(condition.children.map { convert(it) })
        }.build()
    }

    override fun or(condition: Condition): Query {
        return Query.Builder().bool { builder ->
            builder.should(condition.children.map { convert(it) })
                .minimumShouldMatch("1")
        }.build()
    }

    override fun nor(condition: Condition): Query {
        return Query.Builder().bool { builder ->
            builder.mustNot(condition.children.map { convert(it) })
        }.build()
    }

    override fun id(condition: Condition): Query {
        return Query.Builder().ids {
            it.values(condition.valueAs<String>())
        }.build()
    }

    override fun ids(condition: Condition): Query {
        return Query.Builder().ids {
            it.values(condition.valueAs<List<String>>())
        }.build()
    }

    override fun tenantId(condition: Condition): Query {
        return Query.Builder().term {
            it.field(MessageRecords.TENANT_ID)
                .value(FieldValue.of(condition.value))
        }.build()
    }

    override fun all(condition: Condition): Query {
        return Query.Builder().matchAll { it }.build()
    }

    override fun eq(condition: Condition): Query {
        return Query.Builder().term {
            it.field(condition.field)
                .value(FieldValue.of(condition.value))
        }.build()
    }

    override fun ne(condition: Condition): Query {
        TODO()
    }

    override fun gt(condition: Condition): Query {
        return Query.Builder().range {
            it.field(condition.field)
                .gte(JsonData.of(condition.value))
        }.build()
    }

    override fun lt(condition: Condition): Query {
        return Query.Builder().range {
            it.field(condition.field)
                .lte(JsonData.of(condition.value))
        }.build()
    }

    override fun gte(condition: Condition): Query {
        return Query.Builder().range {
            it.field(condition.field)
                .gte(JsonData.of(condition.value))
        }.build()
    }

    override fun lte(condition: Condition): Query {
        return Query.Builder().range {
            it.field(condition.field)
                .lte(JsonData.of(condition.value))
        }.build()
    }

    override fun contains(condition: Condition): Query {
        TODO()
    }

    override fun isIn(condition: Condition): Query {
        TODO("Not yet implemented")
    }

    override fun notIn(condition: Condition): Query {
        TODO("Not yet implemented")
    }

    override fun between(condition: Condition): Query {
        TODO("Not yet implemented")
    }

    override fun allIn(condition: Condition): Query {
        TODO("Not yet implemented")
    }

    override fun startsWith(condition: Condition): Query {
        return Query.Builder().prefix {
            it.field(condition.field)
                .value(condition.valueAs<String>())
        }.build()
    }

    override fun endsWith(condition: Condition): Query {
        TODO("Not yet implemented")
    }

    override fun elemMatch(condition: Condition): Query {
        TODO("Not yet implemented")
    }

    override fun isNull(condition: Condition): Query {
        TODO("Not yet implemented")
    }

    override fun notNull(condition: Condition): Query {
        TODO("Not yet implemented")
    }

    override fun isTrue(condition: Condition): Query {
        TODO("Not yet implemented")
    }

    override fun isFalse(condition: Condition): Query {
        TODO("Not yet implemented")
    }

    override fun deleted(condition: Condition): Query {
        TODO("Not yet implemented")
    }

    override fun today(condition: Condition): Query {
        TODO("Not yet implemented")
    }

    override fun tomorrow(condition: Condition): Query {
        TODO("Not yet implemented")
    }

    override fun thisWeek(condition: Condition): Query {
        TODO("Not yet implemented")
    }

    override fun nextWeek(condition: Condition): Query {
        TODO("Not yet implemented")
    }

    override fun lastWeek(condition: Condition): Query {
        TODO("Not yet implemented")
    }

    override fun thisMonth(condition: Condition): Query {
        TODO("Not yet implemented")
    }

    override fun lastMonth(condition: Condition): Query {
        TODO("Not yet implemented")
    }

    override fun recentDays(condition: Condition): Query {
        TODO("Not yet implemented")
    }

    override fun raw(condition: Condition): Query {
        TODO("Not yet implemented")
    }

    fun Condition.toQuery(): Query {
        return convert(this)
    }
}