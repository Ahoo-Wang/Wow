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

package me.ahoo.wow.query.mask

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.sameInstance
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class DefaultAggregateDataMaskerTest {
    @Test
    fun main() {
        val mockStateDataMasker = MockStateDataMasker(MOCK_AGGREGATE_METADATA)
        val aggregateDataMasker = DefaultAggregateDataMasker.empty<StateDynamicDocumentMasker>()
            .addMasker(mockStateDataMasker)
        val dynamicDocument = mutableMapOf<String, Any>().toDynamicDocument()
        aggregateDataMasker.mask(dynamicDocument)
        assertThat(dynamicDocument.getValue(MockStateDataMasker.KEY), equalTo(MockStateDataMasker.VALUE))
        assert(aggregateDataMasker.maskers.size == 1)
        val aggregateDataMasker2 = aggregateDataMasker.removeMasker(mockStateDataMasker)
        assert(aggregateDataMasker2.maskers.isEmpty())
    }

    @Test
    fun maskEmptyPagedList() {
        val pagedList = PagedList(0, emptyList<DynamicDocument>())
        val maskedPagedList = DefaultAggregateDataMasker.empty<StateDynamicDocumentMasker>()
            .mask(pagedList)
        assertThat(maskedPagedList, sameInstance(pagedList))
    }

    @Test
    fun maskPagedListEmptyMasker() {
        val pagedList = PagedList(1, listOf<DynamicDocument>(mutableMapOf<String, Any>().toDynamicDocument()))
        val maskedPagedList = DefaultAggregateDataMasker.empty<StateDynamicDocumentMasker>()
            .mask(pagedList)
        assertThat(maskedPagedList, sameInstance(pagedList))
    }

    @Test
    fun maskPagedList() {
        val mockStateDataMasker = MockStateDataMasker(MOCK_AGGREGATE_METADATA)
        val aggregateDataMasker = DefaultAggregateDataMasker.empty<StateDynamicDocumentMasker>()
            .addMasker(mockStateDataMasker)
        val pagedList = PagedList(1, listOf<DynamicDocument>(mutableMapOf<String, Any>().toDynamicDocument()))
        val maskedPagedList = aggregateDataMasker.mask(pagedList)
        assertThat(maskedPagedList.list[0].getValue(MockStateDataMasker.KEY), equalTo(MockStateDataMasker.VALUE))
    }
}

class MockStateDataMasker(override val namedAggregate: NamedAggregate) : StateDynamicDocumentMasker {
    companion object {
        const val KEY = "key"
        const val VALUE = "value"
    }

    override fun mask(dynamicDocument: DynamicDocument): DynamicDocument {
        dynamicDocument.put(KEY, VALUE)
        return dynamicDocument
    }
}
