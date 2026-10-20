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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.datasetregistry.starter.RegistryFixtures.document
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.schema.QueryDataset
import me.ahoo.wow.query.schema.QueryDatasets
import me.ahoo.wow.serialization.toObjectNode
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

class DatasetRegistryProjectionTest {
    private val orders = MaterializedNamedAggregate("analytics", "orders")
    private val customers = MaterializedNamedAggregate("analytics", "customers")
    private val fromFile = QueryDataset.parse(MaterializedNamedAggregate("analytics", "files"), document().toString())

    @Test
    fun `the published datasets are served, replaced by their next version and removed when retired`() {
        val published = CopyOnWriteArrayList<PublishedDataset>()
        val datasets = QueryDatasets(listOf(fromFile))
        val projection = DatasetRegistryProjection(datasets, { Flux.fromIterable(published) }, Duration.ofHours(1))

        published += PublishedDataset("analytics.orders", 1, document())
        published += PublishedDataset("analytics.customers", 1, document("name"))
        published += PublishedDataset("analytics.files", 1, document("region"))
        published += PublishedDataset("nameless", 1, document())
        published += PublishedDataset("analytics.broken", 1, "{}".toObjectNode())
        projection.refresh().block()
        datasets.all.map { it.namedAggregate.aggregateName }.assert()
            .containsExactlyInAnyOrder("files", "orders", "customers")
        projection.names.assert().containsExactlyInAnyOrder(orders, customers)
        datasets[MaterializedNamedAggregate("analytics", "files")].assert().isSameAs(fromFile)

        val before = datasets[orders]
        projection.refresh().block()
        datasets[orders].assert().isSameAs(before)

        published.removeIf { it.name == "analytics.orders" || it.name == "analytics.customers" }
        published += PublishedDataset("analytics.orders", 2, document("region"))
        projection.refresh().block()
        datasets[orders]!!.declaration.fields.keys.map { it.path }.assert().contains("region")
        datasets[customers].assert().isNull()
        projection.names.assert().containsExactly(orders)
    }

    @Test
    fun `started, it serves the published datasets until stopped`() {
        val datasets = QueryDatasets()
        val projection = DatasetRegistryProjection(
            datasets,
            { Flux.just(PublishedDataset("analytics.orders", 1, document())) },
            Duration.ofMillis(50),
        )
        projection.isRunning.assert().isFalse()
        projection.start()
        projection.isRunning.assert().isTrue()
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (datasets[orders] == null && System.nanoTime() < deadline) Thread.sleep(10)
        datasets[orders].assert().isNotNull()
        projection.stop()
        projection.isRunning.assert().isFalse()

        val failing = DatasetRegistryProjection(QueryDatasets(), {
            Flux.error(IllegalStateException("down"))
        }, Duration.ofMillis(50))
        failing.start()
        failing.isRunning.assert().isTrue()
        failing.stop()
    }
}
