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

package me.ahoo.wow.datasetregistry.starter

import me.ahoo.wow.datasetregistry.domain.DatasetState
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.query.snapshot.query
import reactor.core.publisher.Flux
import tools.jackson.databind.node.ObjectNode

/** One published dataset: its name (`{context}.{dataset}`), its version and the declaration served. */
data class PublishedDataset(val name: String, val version: Int, val document: ObjectNode)

/** Where the projection reads the published datasets from. */
fun interface PublishedDatasetSource {
    fun published(): Flux<PublishedDataset>
}

/** The published datasets of the registry's snapshots. */
class SnapshotPublishedDatasetSource(
    private val snapshotQueryGateway: () -> SnapshotQueryGateway<DatasetState>,
) : PublishedDatasetSource {
    override fun published(): Flux<PublishedDataset> =
        listQuery { limit(MAX_DATASETS) }.query(snapshotQueryGateway()).mapNotNull { snapshot ->
            snapshot.state.published?.let {
                PublishedDataset(
                    snapshot.aggregateId,
                    snapshot.state.publishedVersion,
                    it
                )
            }
        }

    companion object {
        /** The most datasets one registry serves. */
        const val MAX_DATASETS = 10_000
    }
}
