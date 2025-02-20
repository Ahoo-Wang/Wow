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

import co.elastic.clients.elasticsearch._types.query_dsl.Query
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.elasticsearch.query.snapshot.SnapshotConditionConverter
import me.ahoo.wow.query.dsl.condition
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ElasticsearchConditionConverterTest {
    @Test
    fun `all condition to Query`() {
        val query = condition { }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.MatchAll))
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
        assertThat(query._kind(), equalTo(Query.Kind.Bool))
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
        assertThat(query._kind(), equalTo(Query.Kind.Bool))
        assertThat(query.bool().should().isNotEmpty(), equalTo(true))
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
        assertThat(query._kind(), equalTo(Query.Kind.Bool))
        assertThat(query.bool().mustNot().isNotEmpty(), equalTo(true))
    }

    @Test
    fun `id condition to Query`() {
        val query = condition {
            id("1")
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.Ids))
    }

    @Test
    fun `ids condition to Query`() {
        val query = condition {
            ids(listOf("1", "2"))
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.Ids))
    }

    @Test
    fun `tenantId condition to Query`() {
        val query = condition {
            tenantId("1")
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.Term))
    }

    @Test
    fun `ownerId condition to Query`() {
        val query = condition {
            ownerId("1")
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.Term))
    }

    @Test
    fun `eq condition to Query`() {
        val query = condition {
            "field" eq "value"
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.Term))
    }

    @Test
    fun `ne condition to Query`() {
        val query = condition {
            "field" ne "value"
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.Bool))
    }

    @Test
    fun `gt condition to Query`() {
        val query = condition {
            "field" gt "value"
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.Range))
    }

    @Test
    fun `gte condition to Query`() {
        val query = condition {
            "field" gte "value"
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.Range))
    }

    @Test
    fun `lt condition to Query`() {
        val query = condition {
            "field" lt "value"
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.Range))
    }

    @Test
    fun `lte condition to Query`() {
        val query = condition {
            "field" lte "value"
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.Range))
    }

    @Test
    fun `contains condition to Query`() {
        val query = condition {
            "field" contains "value"
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.MatchPhrase))
    }

    @Test
    fun `isIn condition to Query`() {
        val query = condition {
            "field" isIn listOf("value")
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.Terms))
    }

    @Test
    fun `notIn condition to Query`() {
        val query = condition {
            "field" notIn listOf("value")
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.Bool))
    }

    @Test
    fun `between condition to Query`() {
        val query = condition {
            "field" between 1 to 2
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.Range))
    }

    @Test
    fun `between condition to Query - should throw exception when value is empty`() {
        val exception = assertThrows<IllegalArgumentException> {
            Condition("field", Operator.BETWEEN, listOf<Int>()).let {
                SnapshotConditionConverter.convert(it)
            }
        }
        assertThat(exception.message, equalTo("BETWEEN operator value must be a array with 2 elements."))
    }

    @Test
    fun `between condition to Query - should throw exception when value just one`() {
        val exception = assertThrows<IllegalArgumentException> {
            Condition("field", Operator.BETWEEN, listOf(1)).let {
                SnapshotConditionConverter.convert(it)
            }
        }
        assertThat(exception.message, equalTo("BETWEEN operator value must be a array with 2 elements."))
    }

    @Test
    fun `allIn condition to Query`() {
        val query = condition {
            "field" all listOf("value1", "value2")
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.TermsSet))
    }

    @Test
    fun `startsWith condition to Query`() {
        val query = condition {
            "field" startsWith "value"
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.Prefix))
    }

    @Test
    fun `endsWith condition to Query`() {
        val query = condition {
            "field" endsWith "value"
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.Wildcard))
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
        assertThat(query._kind(), equalTo(Query.Kind.Nested))
    }

    @Test
    fun `isNull condition to Query`() {
        val query = condition {
            "field".isNull()
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.Term))
    }

    @Test
    fun `notNull condition to Query`() {
        val query = condition {
            "field".notNull()
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.Bool))
    }

    @Test
    fun `isTrue condition to Query`() {
        val query = condition {
            "field".isTrue()
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.Term))
    }

    @Test
    fun `isFalse condition to Query`() {
        val query = condition {
            "field".isFalse()
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.Term))
    }

    @Test
    fun `exists condition to Query`() {
        val query = condition {
            "field".exists()
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.Exists))
    }

    @Test
    fun `not exists condition to Query`() {
        val query = condition {
            "field".exists(false)
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.Bool))
    }

    @Test
    fun `deleted condition to Query`() {
        val query = condition {
            deleted(true)
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.Term))
    }

    @Test
    fun `today condition to Query`() {
        val query = condition {
            "field".today()
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.Range))
    }

    @Test
    fun `raw to query`() {
        val rawQuery = Query.Builder().matchAll { it }.build()
        val query = condition {
            raw(rawQuery)
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query, equalTo(rawQuery))
    }

    @Test
    fun `string raw to query`() {
        val query = condition {
            raw("""{"match_all":{}}""")
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.MatchAll))
    }

    @Test
    fun `map raw to query`() {
        val query = condition {
            raw(mapOf("match_all" to emptyMap<String, String>()))
        }.let {
            SnapshotConditionConverter.convert(it)
        }
        assertThat(query._kind(), equalTo(Query.Kind.MatchAll))
    }
}
