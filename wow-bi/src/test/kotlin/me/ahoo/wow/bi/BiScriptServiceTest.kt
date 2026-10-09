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

package me.ahoo.wow.bi

import me.ahoo.test.asserts.assert
import me.ahoo.wow.configuration.MetadataSearcher
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BiScriptServiceTest {
    private val options = BiScriptOptions(consumerGroupNamespace = "test", topology = ClickHouseTopology.Standalone)
    private val descriptor = BiDeploymentDescriptor.from(options)
    private val aggregate = MetadataSearcher.localAggregates.single { it.aggregateName == "aggregate" }

    @Test
    fun `should reset an observed deployment through its inspector`() {
        val store = ObservedBiObject(
            database = options.database,
            name = "bi_gone_state_last_store",
            engine = "ReplacingMergeTree",
            metadata = BiObjectMetadata(
                deploymentId = descriptor.deploymentId,
                kind = BiObjectKind.STORE,
                aggregate = "bi.gone",
            ),
        )
        val service = BiScriptService(
            BiDeploymentInspector { _, _, _ ->
                Mono.just(BiDeploymentInspection.Available(ObservedBiDeployment(listOf(store))))
            }
        )

        val result = service.generate(options, BiScriptOperation.Reset(true)) { setOf(aggregate) }.block()!!

        result.destructive.assert().isTrue()
        result.script.assert().contains("DROP TABLE IF EXISTS \"bi_db\".\"bi_gone_state_last_store\"")
    }

    @Test
    fun `should inspect with the request options and operation`() {
        val changed = options.copy(kafkaBootstrapServers = "changed-kafka:9092")
        lateinit var inspected: Pair<BiScriptOptions, BiScriptOperation>
        val service = BiScriptService(
            BiDeploymentInspector { inspectedOptions, operation, _ ->
                inspected = inspectedOptions to operation
                Mono.just(BiDeploymentInspection.Available(ObservedBiDeployment(emptyList())))
            }
        )

        val result = service.generate(changed, BiScriptOperation.Reset(true)) { setOf(aggregate) }.block()!!

        inspected.assert().isEqualTo(changed to BiScriptOperation.Reset(true))
        result.script.assert().contains("changed-kafka:9092")
    }

    @Test
    fun `should resolve aggregates and render on its scheduler`() {
        val scheduler = Schedulers.newSingle("bi-service-test")
        val inspection = Schedulers.newSingle("bi-inspection-test")
        try {
            lateinit var resolvedOn: String
            lateinit var renderedOn: String
            // The inspection completes on its own thread; rendering must move back onto the service's scheduler.
            val inspector = BiDeploymentInspector { _, _, _ ->
                Mono.just<BiDeploymentInspection>(BiDeploymentInspection.Unavailable).publishOn(inspection)
            }
            BiScriptService(inspector, scheduler).generate(options) {
                resolvedOn = Thread.currentThread().name
                setOf(aggregate)
            }.doOnNext { renderedOn = Thread.currentThread().name }.block()!!

            resolvedOn.assert().startsWith("bi-service-test")
            renderedOn.assert().startsWith("bi-service-test")
        } finally {
            scheduler.dispose()
            inspection.dispose()
        }
    }

    @Test
    fun `should report a saturated scheduler as unavailable`() {
        val scheduler = Schedulers.newBoundedElastic(1, 1, "saturated-bi-service", 60, true)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        scheduler.schedule {
            started.countDown()
            release.await()
        }
        started.await(5, TimeUnit.SECONDS).assert().isTrue()
        scheduler.schedule { release.await() }
        try {
            assertThrows<BiDeploymentInspectionException.Unavailable> {
                BiScriptService(scheduler = scheduler).generate(options) { setOf(aggregate) }.block()
            }.message.assert().isEqualTo("Wow BI script generation is overloaded")
        } finally {
            release.countDown()
            scheduler.dispose()
        }
    }
}
