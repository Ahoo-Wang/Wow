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

package me.ahoo.wow.query.index

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.schema.QueryCardinality
import me.ahoo.wow.query.schema.QueryStorageType
import org.junit.jupiter.api.Test

class QueryIndexPlanTest {
    private val key = QueryIndexPlan.Key(
        QueryField("state.status"),
        QueryField("state.status"),
        setOf(QueryStorageType.of("text")),
        QueryCardinality.SINGLE,
    )

    @Test
    fun `a planned index and its keys should be values`() {
        val same = QueryIndexPlan.Key(
            QueryField("state.status"),
            QueryField("state.status"),
            setOf(QueryStorageType.of("text")),
            QueryCardinality.SINGLE
        )
        key.assert().isEqualTo(
            same
        ).isNotEqualTo(QueryIndexPlan.Key(QueryField("state.a"), QueryField("state.a"), null, null))
        key.hashCode().assert().isEqualTo(same.hashCode())
        key.toString().assert().contains("field=state.status", "cardinality=SINGLE")
        val index = QueryIndexPlan.Index("ix", listOf(key))
        index.assert().isEqualTo(
            QueryIndexPlan.Index("ix", listOf(same))
        ).isNotEqualTo(QueryIndexPlan.Index("iy", listOf(key)))
        index.hashCode().assert().isEqualTo(QueryIndexPlan.Index("ix", listOf(same)).hashCode())
        index.toString().assert().startsWith("Index(name=ix, keys=[Key(")
    }
}
