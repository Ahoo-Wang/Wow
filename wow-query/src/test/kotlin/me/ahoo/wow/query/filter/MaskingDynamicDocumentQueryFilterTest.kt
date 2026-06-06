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

package me.ahoo.wow.query.filter

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.query.mask.AggregateDataMasker
import me.ahoo.wow.query.mask.AggregateDynamicDocumentMasker
import me.ahoo.wow.query.mask.DataMaskerRegistry
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class MaskingDynamicDocumentQueryFilterTest {

    private class MockMaskerRegistry(
        private val aggregateDataMasker: AggregateDataMasker<AggregateDynamicDocumentMasker>
    ) : DataMaskerRegistry<AggregateDynamicDocumentMasker> {
        @Suppress("EmptyFunctionBlock")
        override fun register(masker: AggregateDynamicDocumentMasker) {}

        @Suppress("EmptyFunctionBlock")
        override fun unregister(masker: AggregateDynamicDocumentMasker) {}
        override fun getAggregateDataMasker(
            namedAggregate: NamedAggregate
        ): AggregateDataMasker<AggregateDynamicDocumentMasker> {
            return aggregateDataMasker
        }
    }

    private class MockMaskingFilter(
        maskerRegistry: DataMaskerRegistry<AggregateDynamicDocumentMasker>
    ) : MaskingDynamicDocumentQueryFilter<AggregateDynamicDocumentMasker>(maskerRegistry)

    @Test
    fun `should skip masking for non-dynamic query type`() {
        val mockAggregateMasker = mockk<AggregateDataMasker<AggregateDynamicDocumentMasker>>()
        val registry = MockMaskerRegistry(mockAggregateMasker)
        val filter = MockMaskingFilter(registry)
        val context = DefaultQueryContext<Any, Any>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        val chain = mockk<FilterChain<QueryContext<*, *>>> {
            every { filter(context) } returns Mono.empty()
        }
        filter.filter(context, chain).test().verifyComplete()
    }

    @Test
    fun `should skip masking when masker is empty`() {
        val mockAggregateMasker = mockk<AggregateDataMasker<AggregateDynamicDocumentMasker>> {
            every { isEmpty() } returns true
        }
        val registry = MockMaskerRegistry(mockAggregateMasker)
        val filter = MockMaskingFilter(registry)
        val context = DefaultQueryContext<Any, Any>(
            queryType = QueryType.DYNAMIC_SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        val chain = mockk<FilterChain<QueryContext<*, *>>> {
            every { filter(context) } returns Mono.empty()
        }
        filter.filter(context, chain).test().verifyComplete()
    }

    @Test
    fun `should mask dynamic single result`() {
        val maskedDoc = mutableMapOf("field" to "masked").toDynamicDocument()
        val mockAggregateMasker = mockk<AggregateDataMasker<AggregateDynamicDocumentMasker>> {
            every { isEmpty() } returns false
            every { mask(any<DynamicDocument>()) } returns maskedDoc
        }
        val registry = MockMaskerRegistry(mockAggregateMasker)
        val filter = MockMaskingFilter(registry)
        val context = DefaultQueryContext<Any, Any>(
            queryType = QueryType.DYNAMIC_SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        val originalDoc = mutableMapOf("field" to "original").toDynamicDocument()
        context.setResult(Mono.just(originalDoc))
        val chain = mockk<FilterChain<QueryContext<*, *>>> {
            every { filter(context) } returns Mono.empty()
        }
        filter.filter(context, chain).test().verifyComplete()
        @Suppress("UNCHECKED_CAST")
        val result = context.getRequiredResult() as Mono<DynamicDocument>
        result.block().assert().isEqualTo(maskedDoc)
    }

    @Test
    fun `should mask dynamic list result`() {
        val maskedDoc = mutableMapOf("field" to "masked").toDynamicDocument()
        val mockAggregateMasker = mockk<AggregateDataMasker<AggregateDynamicDocumentMasker>> {
            every { isEmpty() } returns false
            every { mask(any<DynamicDocument>()) } returns maskedDoc
        }
        val registry = MockMaskerRegistry(mockAggregateMasker)
        val filter = MockMaskingFilter(registry)
        val context = DefaultQueryContext<Any, Any>(
            queryType = QueryType.DYNAMIC_LIST,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        val originalDoc = mutableMapOf("field" to "original").toDynamicDocument()
        context.setResult(Flux.just(originalDoc))
        val chain = mockk<FilterChain<QueryContext<*, *>>> {
            every { filter(context) } returns Mono.empty()
        }
        filter.filter(context, chain).test().verifyComplete()
    }

    @Test
    fun `should mask dynamic paged result`() {
        val maskedDoc = mutableMapOf("field" to "masked").toDynamicDocument()
        val mockAggregateMasker = mockk<AggregateDataMasker<AggregateDynamicDocumentMasker>> {
            every { isEmpty() } returns false
            every { mask(any<DynamicDocument>()) } returns maskedDoc
        }
        val registry = MockMaskerRegistry(mockAggregateMasker)
        val filter = MockMaskingFilter(registry)
        val context = DefaultQueryContext<Any, Any>(
            queryType = QueryType.DYNAMIC_PAGED,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        val originalDoc = mutableMapOf("field" to "original").toDynamicDocument()
        context.setResult(Mono.just(PagedList(1, listOf(originalDoc))))
        val chain = mockk<FilterChain<QueryContext<*, *>>> {
            every { filter(context) } returns Mono.empty()
        }
        filter.filter(context, chain).test().verifyComplete()
    }
}
