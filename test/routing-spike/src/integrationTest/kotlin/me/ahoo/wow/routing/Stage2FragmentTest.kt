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

package me.ahoo.wow.routing

import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemoryReservationStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.manifest.ManifestLoader
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLClassLoader

/**
 * Stage 2's Manifest fragment (`src/stage2/resources/META-INF/wow/manifest/h2-stage2.json`) agrees with the model its
 * nodes serve, as a node checks it at its start: read by the loader a host reads it with, then given to a
 * [ServiceNode], whose assembly refuses a Manifest and models that disagree. Stage 2 runs on demand alone, so this is
 * what keeps its fragment from going stale unseen (#7006): it runs with stage 1's integration tests, with no JVM of
 * its own.
 */
@OptIn(InternalWowApi::class)
class Stage2FragmentTest {
    /** What stage 2's `H2Models` provides: stage 1's account in the context `h2`, and the fragment that lists it. */
    private object Stage2Models : ModelProvider {
        override val context: String = "h2"
        override val binding: Int = ModelProvider.BINDING
        override val fragment: String = "META-INF/wow/manifest/h2-stage2.json"

        override fun models(dependencies: Dependencies): List<AggregateModel<*>> = listOf(accountModel(context, 0))
    }

    @Test
    fun `stage 2's fragment should agree with the model its nodes serve, as a node checks it at its start`() {
        val resources = File(checkNotNull(System.getProperty(RESOURCES)) { "$RESOURCES is not set." })
        val manifest = URLClassLoader(arrayOf(resources.toURI().toURL()), javaClass.classLoader).use { loader ->
            ManifestLoader(loader).load(ServiceInfo(SERVICE), listOf(Stage2Models))
        }
        // The node's assembly checks the Manifest against the models (ManifestCheck) and fails on any difference, then
        // opens its backends, in memory here: stage 2's PostgreSQL is no part of the check.
        val node = ServiceNode(
            SERVICE,
            "node-0",
            null,
            Stage2Models.models(Dependencies.NONE),
            manifest,
            BackendFactory {
                ContextBackends(
                    log = InMemoryEventLog(PARTITIONS),
                    checkpoints = InMemoryCheckpointStore(),
                    leases = InMemoryLeaseStore(),
                    ledger = InMemoryFailureLedger(),
                    snapshots = SnapshotTargets.single(InMemorySnapshotStore()),
                    reservations = InMemoryReservationStore(),
                )
            },
            LoopbackClusterChannel(),
        )
        runBlocking { node.stop() }
    }

    private companion object {
        /** The service stage 2's nodes form (`H2_SERVICE`). */
        const val SERVICE = "h2-stage2"

        const val PARTITIONS = 4

        /** Where the build gives stage 2's resources, which are not on this source set's class path. */
        const val RESOURCES = "wow-test.h2.stage2.resources"
    }
}
