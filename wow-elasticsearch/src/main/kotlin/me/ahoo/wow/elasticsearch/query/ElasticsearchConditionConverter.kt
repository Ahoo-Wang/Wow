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

@file:Suppress("NoWildcardImports")

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.*
import co.elastic.clients.json.JsonData
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.query.converter.AbstractConditionConverter
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import java.time.LocalDateTime
import java.time.ZoneOffset

object ElasticsearchConditionConverter : AbstractConditionConverter<Query>() {
    override fun and(condition: Condition): Query {
        return bool { builder ->
            builder.filter(condition.children.map { convert(it) })
        }
    }

    override fun or(condition: Condition): Query {
        return bool { builder ->
            builder.should(condition.children.map { convert(it) })
                .minimumShouldMatch("1")
        }
    }

    override fun nor(condition: Condition): Query {
        return bool { builder ->
            builder.mustNot(condition.children.map { convert(it) })
        }
    }

    override fun id(condition: Condition): Query {
        return ids {
            it.values(condition.valueAs<String>())
        }
    }

    override fun ids(condition: Condition): Query {
        return ids {
            it.values(condition.valueAs<List<String>>())
        }
    }

    override fun tenantId(condition: Condition): Query {
        return term {
            it.field(MessageRecords.TENANT_ID)
                .value(FieldValue.of(condition.value))
        }
    }

    override fun all(condition: Condition): Query {
        return matchAll { it }
    }

    override fun eq(condition: Condition): Query {
        return term {
            it.field(condition.field)
                .value(FieldValue.of(condition.value))
        }
    }

    override fun ne(condition: Condition): Query {
        return bool { builder ->
            builder.mustNot(eq(condition))
        }
    }

    override fun gt(condition: Condition): Query {
        return range {
            it.field(condition.field)
                .gte(JsonData.of(condition.value))
        }
    }

    override fun lt(condition: Condition): Query {
        return range {
            it.field(condition.field)
                .lte(JsonData.of(condition.value))
        }
    }

    override fun gte(condition: Condition): Query {
        return range {
            it.field(condition.field)
                .gte(JsonData.of(condition.value))
        }
    }

    override fun lte(condition: Condition): Query {
        return range {
            it.field(condition.field)
                .lte(JsonData.of(condition.value))
        }
    }

    override fun contains(condition: Condition): Query {
        return matchPhrase {
            it.field(condition.field)
                .query(condition.valueAs<String>())
        }
    }

    override fun isIn(condition: Condition): Query {
        return terms {
            it.field(condition.field)
                .terms { builder ->
                    condition.valueAs<List<Any>>().map {
                        FieldValue.of(it)
                    }.toList().let { builder.value(it) }
                }
        }
    }

    override fun notIn(condition: Condition): Query {
        return bool { builder ->
            builder.mustNot(isIn(condition))
        }
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
        return range {
            it.field(condition.field)
                .gte(JsonData.of(first))
                .lte(JsonData.of(second))
        }
    }

    override fun allIn(condition: Condition): Query {
        val values = condition.valueAs<List<String>>()
        return termsSet { builder ->
            builder.field(condition.field)
                .terms(values)
                .minimumShouldMatchScript {
                    it.inline {
                        it.source(values.size.toString())
                    }
                }
        }
    }

    override fun startsWith(condition: Condition): Query {
        return prefix {
            it.field(condition.field)
                .value(condition.valueAs<String>())
        }
    }

    override fun endsWith(condition: Condition): Query {
        return wildcard {
            it.field(condition.field)
                .value("*${condition.valueAs<String>()}")
        }
    }

    override fun elemMatch(condition: Condition): Query {
        return nested {
            it.path(condition.field)
                .query(
                    bool { builder ->
                        builder.filter(condition.children.map { convert(it) })
                    }
                )
        }
    }

    override fun isNull(condition: Condition): Query {
        return term {
            it.field(condition.field)
                .value(FieldValue.NULL)
        }
    }

    override fun notNull(condition: Condition): Query {
        return bool {
            it.mustNot(isNull(condition))
        }
    }

    override fun isTrue(condition: Condition): Query {
        return term {
            it.field(condition.field)
                .value(FieldValue.TRUE)
        }
    }

    override fun isFalse(condition: Condition): Query {
        return term {
            it.field(condition.field)
                .value(FieldValue.FALSE)
        }
    }

    override fun deleted(condition: Condition): Query {
        return term {
            it.field(StateAggregateRecords.DELETED)
                .value(FieldValue.of(condition.value))
        }
    }

    override fun timeRange(
        field: String,
        from: LocalDateTime,
        to: LocalDateTime
    ): Query {
        return range {
            it.field(field)
                .gte(JsonData.of(from.toInstant(ZoneOffset.UTC).toEpochMilli()))
                .lte(JsonData.of(to.toInstant(ZoneOffset.UTC).toEpochMilli()))
        }
    }

    override fun raw(condition: Condition): Query {
        require(condition.value is Query) {
            "raw condition value must be a Query."
        }

        return condition.valueAs<Query>()
    }

    fun Condition.toQuery(): Query {
        return convert(this)
    }
}
