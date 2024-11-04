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
import me.ahoo.wow.query.converter.AbstractConditionConverter
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import java.time.LocalDateTime
import java.time.ZoneOffset

object ElasticsearchConditionConverter : AbstractConditionConverter<Query>() {
    const val TENANT_ID_KEYWORD = MessageRecords.TENANT_ID + ".keyword"
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
            it.field(TENANT_ID_KEYWORD)
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
        return Query.Builder().bool { builder ->
            builder.mustNot(eq(condition))
        }.build()
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
        return Query.Builder().match {
            it.field(condition.field)
                .query(condition.valueAs<String>())
        }.build()
    }

    override fun isIn(condition: Condition): Query {
        return Query.Builder().terms {
            it.field(condition.field)
                .terms { builder ->
                    condition.valueAs<List<Any>>().map {
                        FieldValue.of(it)
                    }.toList().let { builder.value(it) }
                }
        }.build()
    }

    override fun notIn(condition: Condition): Query {
        return Query.Builder().bool { builder ->
            builder.mustNot(isIn(condition))
        }.build()
    }

    override fun between(condition: Condition): Query {
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
        return Query.Builder().range {
            it.field(condition.field)
                .gte(JsonData.of(first))
                .lte(JsonData.of(second))
        }.build()
    }

    override fun allIn(condition: Condition): Query {
        TODO()
    }

    override fun startsWith(condition: Condition): Query {
        return Query.Builder().prefix {
            it.field(condition.field)
                .value(condition.valueAs<String>())
        }.build()
    }

    override fun endsWith(condition: Condition): Query {
        return Query.Builder().regexp {
            it.field(condition.field)
                .value(".*${condition.valueAs<String>()}")
        }.build()
    }

    override fun elemMatch(condition: Condition): Query {
        return Query.Builder().nested {
            it.path(condition.field)
                .query(Query.Builder().bool { builder ->
                    builder.filter(condition.children.map { convert(it) })
                }.build())
        }.build()
    }

    override fun isNull(condition: Condition): Query {
        return Query.Builder().term {
            it.field(condition.field)
                .value(FieldValue.NULL)
        }.build()
    }

    override fun notNull(condition: Condition): Query {
        return Query.Builder().bool {
            it.mustNot(isNull(condition))
        }.build()
    }

    override fun isTrue(condition: Condition): Query {
        return Query.Builder().term {
            it.field(condition.field)
                .value(FieldValue.TRUE)
        }.build()
    }

    override fun isFalse(condition: Condition): Query {
        return Query.Builder().term {
            it.field(condition.field)
                .value(FieldValue.FALSE)
        }.build()
    }

    override fun deleted(condition: Condition): Query {
        return Query.Builder().term {
            it.field(StateAggregateRecords.DELETED)
                .value(FieldValue.of(condition.value))
        }.build()
    }

    override fun timeRange(
        field: String,
        from: LocalDateTime,
        to: LocalDateTime
    ): Query {
        return Query.Builder().range {
            it.field(field)
                .gte(JsonData.of(from.toInstant(ZoneOffset.UTC).toEpochMilli()))
                .lte(JsonData.of(to.toInstant(ZoneOffset.UTC).toEpochMilli()))
        }.build()
    }

    override fun raw(condition: Condition): Query {
        return condition.valueAs<Query>()
    }

    fun Condition.toQuery(): Query {
        return convert(this)
    }
}