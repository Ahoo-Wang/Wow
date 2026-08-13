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

package me.ahoo.wow.elasticsearch.query.backend

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.ImmutableDynamicDocument
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.plan.QueryPlanResultShape
import me.ahoo.wow.tck.query.backend.PortableQueryDataset
import me.ahoo.wow.tck.query.backend.PortableQueryResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ElasticsearchQueryResultDecoderTest {
    private val binding = ElasticsearchQueryFieldBinding.bind(
        PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT),
    )
    private val decoder = ElasticsearchQueryResultDecoder(binding)

    @Test
    fun `dynamic projection is validated and strips recursive presence metadata`() {
        val shape = QueryPlanResultShape.Dynamic(
            setOf(PortableQueryDataset.LOGICAL_ID, PortableQueryDataset.PROFILE),
        )
        val projection = binding.projection(shape.fields)

        val result = decoder.decode<ImmutableDynamicDocument>(
            mapOf(
                "logicalId" to "d01",
                "profile" to mapOf(
                    "city" to "杭州",
                    "__wow_query" to mapOf("present" to listOf("city")),
                ),
                "__wow_query" to mapOf("present" to listOf("logicalId", "profile")),
            ),
            shape,
            projection,
        )

        result["logicalId"].assert().isEqualTo("d01")
        result["profile"].assert().isEqualTo(mapOf("city" to "杭州"))
    }

    @Test
    fun `typed projection is reconstructed without retaining backend source`() {
        val shape = QueryPlanResultShape.Typed(
            PortableQueryResult::class.java,
            setOf(PortableQueryDataset.LOGICAL_ID),
        )

        decoder.decode<PortableQueryResult>(
            mapOf("logicalId" to "d01", "title" to "not projected"),
            shape,
            binding.projection(shape.fields),
        ).assert().isEqualTo(PortableQueryResult("d01"))
    }

    @Test
    fun `missing non-null field and wrong scalar type fail closed`() {
        val logicalId = QueryPlanResultShape.Dynamic(setOf(PortableQueryDataset.LOGICAL_ID))
        assertThrows<QueryException> {
            decoder.decode<ImmutableDynamicDocument>(emptyMap(), logicalId, binding.projection(logicalId.fields))
        }

        val rank = QueryPlanResultShape.Dynamic(setOf(PortableQueryDataset.RANK))
        assertThrows<QueryException> {
            decoder.decode<ImmutableDynamicDocument>(
                mapOf("rank" to "1"),
                rank,
                binding.projection(rank.fields),
            )
        }
    }
}
