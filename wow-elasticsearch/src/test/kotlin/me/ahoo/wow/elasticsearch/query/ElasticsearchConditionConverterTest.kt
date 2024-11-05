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
import me.ahoo.wow.elasticsearch.query.ElasticsearchConditionConverter.toQuery
import me.ahoo.wow.query.dsl.condition
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.Test

class ElasticsearchConditionConverterTest {
    @Test
    fun `should convert condition to Query`() {
        val query = condition { }.toQuery()
        assertThat(query._kind(), equalTo(Query.Kind.MatchAll))
    }

    @Test
    fun rawConvert() {
        val rawQuery = Query.Builder().matchAll { it }.build()
        val query = condition {
            raw(rawQuery)
        }.toQuery()
        assertThat(query, equalTo(rawQuery))
    }
}
