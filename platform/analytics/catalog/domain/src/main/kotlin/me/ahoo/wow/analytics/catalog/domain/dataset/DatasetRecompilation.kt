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

package me.ahoo.wow.analytics.catalog.domain.dataset

import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetStatus
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetVersion
import me.ahoo.wow.analytics.catalog.api.dataset.RecompileDataset
import me.ahoo.wow.analytics.catalog.domain.source.SourceState
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.Saga
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.query.dsl.listQuery
import me.ahoo.wow.api.read.snapshots
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateEvent

/**
 * Recompiles the published datasets a source's change may break (-2): every
 * commit of a source sends `RecompileDataset` to each published dataset whose published definition reads it (or
 * that needs revision already), read from the datasets' snapshots; each dataset then compiles its published definition against the source as it is
 * now.
 */
@Saga
class DatasetRecompilation {
    @OnEvent
    suspend fun onSourceChanged(event: StateEvent<SourceState>, context: SagaContext): List<Command<RecompileDataset>> {
        val source = event.ref.id
        // A job that reads every dataset, not a request: the datasets of the catalog, which is the platform's.
        val readers = context.queries.trusted().unbounded().snapshots<DatasetState>()
            .list(listQuery { })
            .map { it.state }
            .filter { dataset -> dataset.status in RECOMPILED && dataset.published().reads(source) }
            .toList()
        return readers.map { dataset ->
            val id = AnalyticsCatalogContext.datasetIdOf(dataset.context, dataset.name)
            context.command(DATASET.ref(AnalyticsCatalogContext.TENANT_ID, id), RecompileDataset)
        }
    }

    private fun DatasetVersion?.reads(source: String): Boolean =
        this?.definition?.sources?.any { it.sourceId == source } == true

    private companion object {
        val DATASET = DatasetModel.TYPE
        val RECOMPILED = setOf(DatasetStatus.PUBLISHED, DatasetStatus.NEEDS_REVISION)
    }
}
