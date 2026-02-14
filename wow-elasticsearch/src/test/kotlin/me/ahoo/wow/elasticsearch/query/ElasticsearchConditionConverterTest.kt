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
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.bool
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.exists
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.ids
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.matchAll
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.matchPhrase
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.nested
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.prefix
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.range
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.term
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.terms
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.termsSet
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.wildcard
import co.elastic.clients.json.JsonData
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.elasticsearch.WowJsonpMapper
import me.ahoo.wow.elasticsearch.query.snapshot.SnapshotConditionConverter
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ElasticsearchConditionConverterTest {
    private fun assertConvert(actual: Query, expected: Query) {
        actual._kind().assert().isEqualTo(Query.Kind.Bool)
        val actualDeletedQuery = actual.bool().filter().first().term()
        actualDeletedQuery.field().assert().isEqualTo(StateAggregateRecords.DELETED)
        actualDeletedQuery.value().booleanValue().assert().isFalse()
        val actualQuery = actual.bool().filter().last()
        val actualGen = WowJsonpMapper.createBufferingGenerator()
        actualQuery.serialize(actualGen, WowJsonpMapper)
        val expectedGen = WowJsonpMapper.createBufferingGenerator()
        expected.serialize(expectedGen, WowJsonpMapper)
        actualGen.jsonData.toJson().toString().assert().isEqualTo(expectedGen.jsonData.toJson().toString())
    }

    @Test
    fun `all condition to Query`() {
        val query = condition { }.let {
            SnapshotConditionConverter.convert(it)
        }
        query.toString().assert().isEqualTo(
            term {
                it.field(StateAggregateRecords.DELETED)
                    .value(false)
            }.toString()
        )
    }

    @Test
    fun `and condition to Query`() {
        val query = condition {
            and {
                id("1")
            }
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            bool { boolBuilder ->
                boolBuilder.filter { filterBuilder ->
                    filterBuilder.ids {
                        it.values("1")
                    }
                }
            }
        )
    }

    @Test
    fun `or condition to Query`() {
        val query = condition {
            or {
                id("1")
            }
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            bool { boolBuilder ->
                boolBuilder.should { shouldBuilder ->
                    shouldBuilder.ids {
                        it.values("1")
                    }
                }.minimumShouldMatch("1")
            }
        )
    }

    @Test
    fun `nor condition to Query`() {
        val query = condition {
            nor {
                id("1")
            }
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            bool { boolBuilder ->
                boolBuilder.mustNot { builder ->
                    builder.ids {
                        it.values("1")
                    }
                }
            }
        )
    }

    @Test
    fun `id condition to Query`() {
        val query = condition {
            id("1")
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            ids {
                it.values("1")
            }
        )
    }

    @Test
    fun `ids condition to Query`() {
        val query = condition {
            ids(listOf("1", "2"))
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            ids {
                it.values("1", "2")
            }
        )
    }

    @Test
    fun `tenantId condition to Query`() {
        val query = condition {
            tenantId("1")
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            term {
                it.field(MessageRecords.TENANT_ID)
                    .value(FieldValue.of("1"))
            }
        )
    }

    @Test
    fun `ownerId condition to Query`() {
        val query = condition {
            ownerId("1")
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            term {
                it.field(MessageRecords.OWNER_ID)
                    .value(FieldValue.of("1"))
            }
        )
    }

    @Test
    fun `eq condition to Query`() {
        val query = condition {
            "field" eq "value"
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            term {
                it.field("field")
                    .value(FieldValue.of("value"))
            }
        )
    }

    @Test
    fun `ne condition to Query`() {
        val query = condition {
            "field" ne "value"
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            bool { boolBuilder ->
                boolBuilder.mustNot { builder ->
                    builder.term {
                        it.field("field")
                            .value(FieldValue.of("value"))
                    }
                }
            }
        )
    }

    @Test
    fun `gt condition to Query`() {
        val query = condition {
            "field" gt "value"
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            range {
                it.untyped {
                    it.field("field")
                        .gt(JsonData.of("value"))
                }
            }
        )
    }

    @Test
    fun `gte condition to Query`() {
        val query = condition {
            "field" gte "value"
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            range {
                it.untyped {
                    it.field("field")
                        .gte(JsonData.of("value"))
                }
            }
        )
    }

    @Test
    fun `lt condition to Query`() {
        val query = condition {
            "field" lt "value"
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            range {
                it.untyped {
                    it.field("field")
                        .lt(JsonData.of("value"))
                }
            }
        )
    }

    @Test
    fun `lte condition to Query`() {
        val query = condition {
            "field" lte "value"
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            range {
                it.untyped {
                    it.field("field")
                        .lte(JsonData.of("value"))
                }
            }
        )
    }

    @Test
    fun `contains condition to Query`() {
        val query = condition {
            "field" contains "value"
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            matchPhrase {
                it.field("field")
                    .query("value")
            }
        )
    }

    @Test
    fun `isIn condition to Query`() {
        val query = condition {
            "field" isIn listOf("value")
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            terms {
                it.field("field")
                    .terms { builder ->
                        listOf("value").map {
                            FieldValue.of(it)
                        }.toList().let { builder.value(it) }
                    }
            }
        )
    }

    @Test
    fun `notIn condition to Query`() {
        val query = condition {
            "field" notIn listOf("value")
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        query._kind().assert().isEqualTo(Query.Kind.Bool)
    }

    @Test
    fun `between condition to Query`() {
        val query = condition {
            "field" between 1 to 2
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            range {
                it.untyped {
                    it.field("field")
                        .gte(JsonData.of(1))
                        .lte(JsonData.of(2))
                }
            }
        )
    }

    @Test
    fun `between condition to Query - should throw exception when value is empty`() {
        val exception = assertThrows<IllegalArgumentException> {
            Condition("field", Operator.BETWEEN, listOf<Int>()).let {
                SnapshotConditionConverter.convert(it)
            }
        }
        exception.message.assert().isEqualTo("BETWEEN operator value must be a array with 2 elements.")
    }

    @Test
    fun `between condition to Query - should throw exception when value just one`() {
        val exception = assertThrows<IllegalArgumentException> {
            Condition("field", Operator.BETWEEN, listOf(1)).let {
                SnapshotConditionConverter.convert(it)
            }
        }
        exception.message.assert().isEqualTo("BETWEEN operator value must be a array with 2 elements.")
    }

    @Test
    fun `allIn condition to Query`() {
        val query = condition {
            "field" all listOf("value1", "value2")
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            termsSet { builder ->
                builder.field("field")
                    .terms("value1", "value2")
                    .minimumShouldMatch("2")
            }
        )
    }

    @Test
    fun `startsWith condition to Query`() {
        val query = condition {
            "field" startsWith "value"
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            prefix {
                it.field("field")
                    .value("value")
            }
        )
    }

    @Test
    fun `endsWith condition to Query`() {
        val query = condition {
            "field" endsWith "value"
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            wildcard {
                it.field("field")
                    .value("*value")
            }
        )
    }

    @Test
    fun `elemMatch condition to Query`() {
        val query = condition {
            "field" elemMatch {
                "subField" eq "value"
            }
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            nested {
                it.path("field")
                    .query(
                        bool { builder ->
                            builder.filter {
                                it.term {
                                    it.field("subField")
                                        .value("value")
                                }
                            }
                        }
                    )
            }
        )
    }

    @Test
    fun `isNull condition to Query`() {
        val query = condition {
            "field".isNull()
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            term {
                it.field("field")
                    .value(FieldValue.NULL)
            }
        )
    }

    @Test
    fun `notNull condition to Query`() {
        val query = condition {
            "field".notNull()
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            bool { boolBuilder ->
                boolBuilder.mustNot { builder ->
                    builder.term {
                        it.field("field")
                            .value(FieldValue.NULL)
                    }
                }
            }
        )
    }

    @Test
    fun `isTrue condition to Query`() {
        val query = condition {
            "field".isTrue()
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            term {
                it.field("field")
                    .value(FieldValue.TRUE)
            }
        )
    }

    @Test
    fun `isFalse condition to Query`() {
        val query = condition {
            "field".isFalse()
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            term {
                it.field("field")
                    .value(FieldValue.FALSE)
            }
        )
    }

    @Test
    fun `exists condition to Query`() {
        val query = condition {
            "field".exists()
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            exists {
                it.field("field")
            }
        )
    }

    @Test
    fun `not exists condition to Query`() {
        val query = condition {
            "field".exists(false)
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            bool { boolBuilder ->
                boolBuilder.mustNot { builder ->
                    builder.exists {
                        it.field("field")
                    }
                }
            }
        )
    }

    @Test
    fun `deleted condition to Query`() {
        val query = condition {
            deleted(true)
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        query.term().field().assert().isEqualTo(StateAggregateRecords.DELETED)
        query.term().value().booleanValue().assert().isTrue()
    }

    @Test
    fun `not deleted condition to Query`() {
        val query = condition {
            deleted(false)
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        query.term().field().assert().isEqualTo(StateAggregateRecords.DELETED)
        query.term().value().booleanValue().assert().isFalse()
    }

    @Test
    fun `deleted condition to Query By ALL`() {
        val query = condition {
            deleted(DeletionState.ALL)
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        query._kind().assert().isEqualTo(Query.Kind.MatchAll)
    }

    @Test
    fun `today condition to Query`() {
        val query = condition {
            "field".today()
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        query._kind().assert().isEqualTo(Query.Kind.Bool)
    }

    @Test
    fun `raw to query`() {
        val rawQuery = Query.Builder().matchAll { it }.build()
        val query = condition {
            raw(rawQuery)
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(query, rawQuery)
    }

    @Test
    fun `string raw to query`() {
        val query = condition {
            raw("""{"match_all":{}}""")
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            matchAll {
                it
            }
        )
    }

    @Test
    fun `map raw to query`() {
        val query = condition {
            raw(mapOf("match_all" to emptyMap<String, String>()))
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertConvert(
            query,
            matchAll {
                it
            }
        )
    }
}
