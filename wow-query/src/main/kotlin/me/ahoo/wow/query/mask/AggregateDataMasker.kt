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

import me.ahoo.wow.annotation.sortedByOrder
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.PagedList

interface AggregateDataMasker<MASKER : DynamicDocumentMasker> : DynamicDocumentMasker {
    val maskers: List<MASKER>
    fun isEmpty(): Boolean = maskers.isEmpty()
    fun addMasker(masker: MASKER): AggregateDataMasker<MASKER>
    fun removeMasker(masker: MASKER): AggregateDataMasker<MASKER>
    override fun mask(dynamicDocument: DynamicDocument): DynamicDocument
}

class DefaultAggregateDataMasker<MASKER : DynamicDocumentMasker>(override val maskers: List<MASKER>) :
    AggregateDataMasker<MASKER> {
    override fun addMasker(masker: MASKER): AggregateDataMasker<MASKER> {
        val sortedMaskers = (maskers + masker).sortedByOrder()
        return DefaultAggregateDataMasker(sortedMaskers)
    }

    override fun removeMasker(masker: MASKER): AggregateDataMasker<MASKER> {
        val mutableMaskers = maskers.toMutableList()
        mutableMaskers.remove(masker)
        val sortedMaskers = mutableMaskers.sortedByOrder()
        return DefaultAggregateDataMasker(sortedMaskers)
    }

    override fun mask(dynamicDocument: DynamicDocument): DynamicDocument {
        var maskedDocument = dynamicDocument
        maskers.forEach {
            maskedDocument = it.mask(maskedDocument)
        }
        return maskedDocument
    }

    companion object {
        val EMPTY = DefaultAggregateDataMasker<DynamicDocumentMasker>(emptyList())

        @Suppress("UNCHECKED_CAST")
        fun <MASKER : DynamicDocumentMasker> empty(): DefaultAggregateDataMasker<MASKER> =
            EMPTY as DefaultAggregateDataMasker<MASKER>
    }
}

fun <MASKER : DynamicDocumentMasker> AggregateDataMasker<MASKER>.mask(pagedList: PagedList<DynamicDocument>): PagedList<DynamicDocument> {
    if (pagedList.list.isEmpty() || isEmpty()) {
        return pagedList
    }

    val maskedList = pagedList.list.map {
        this.mask(it)
    }

    return PagedList(
        total = pagedList.total,
        list = maskedList
    )
}
