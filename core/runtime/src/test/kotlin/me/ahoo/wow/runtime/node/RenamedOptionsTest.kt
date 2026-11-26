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

package me.ahoo.wow.runtime.node

import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.snapshot.SnapshotOptions
import me.ahoo.wow.runtime.timer.TimerOptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The options named as the keys that set them (#7019, D483 ⑮): each refuses a value out of its bounds, naming the
 * bound, and takes the value at it.
 */
class RenamedOptionsTest {
    private fun refused(build: () -> Any): String? = assertThrows<IllegalArgumentException> { build() }.message

    @Test
    fun `a node should take at least one forward from a peer`() {
        refused { ClusterOptions(maxInFlightPerNode = 0) }.assert().isEqualTo("A peer takes at least one forward.")
        ClusterOptions(maxInFlightPerNode = 1).maxInFlightPerNode.assert().isEqualTo(1)
    }

    @Test
    fun `the snapshot maintenance should run at a positive interval, rebuilding at least one snapshot a page and one page a run`() {
        refused { SnapshotOptions(maintenanceInterval = Duration.ZERO) }
            .assert().isEqualTo("The snapshot maintenance runs at a positive interval.")
        refused { SnapshotOptions(rebuildPageSize = 0) }
            .assert().isEqualTo("A rebuild rewrites at least one snapshot a page.")
        refused { SnapshotOptions(rebuildMaxPages = 0) }
            .assert().isEqualTo("A rebuild rewrites at least one page a run.")
        val options = SnapshotOptions(maintenanceInterval = 1.seconds, rebuildPageSize = 1, rebuildMaxPages = 1)
        options.maintenanceInterval.assert().isEqualTo(1.seconds)
        options.rebuildPageSize.assert().isEqualTo(1)
        options.rebuildMaxPages.assert().isEqualTo(1)
    }

    @Test
    fun `the rebuild lease and the revision membership should outlive the maintenance interval`() {
        refused { SnapshotOptions(maintenanceInterval = 10.seconds, leaseTtl = 10.seconds) }
            .assert().isEqualTo("The rebuild lease outlives the interval between runs.")
        refused { SnapshotOptions(maintenanceInterval = 10.seconds, memberTtl = 10.seconds) }
            .assert().isEqualTo("A revision membership outlives the interval between runs.")
        SnapshotOptions(maintenanceInterval = 10.seconds, leaseTtl = 11.seconds, memberTtl = 11.seconds)
            .leaseTtl.assert().isEqualTo(11.seconds)
    }

    @Test
    fun `a timer's claim should hold for a positive time`() {
        refused { TimerOptions(claimTtl = Duration.ZERO) }.assert().isEqualTo("A claim holds for a positive time.")
        TimerOptions(claimTtl = 1.seconds).claimTtl.assert().isEqualTo(1.seconds)
    }

    @Test
    fun `a topic's partitions should be refreshed at a positive interval`() {
        val source = InMemoryTopics(1)
        refused { SourceTopics.of(source, partitionRefreshInterval = Duration.ZERO) }
            .assert().isEqualTo("The partitions of a topic are refreshed at a positive interval.")
        SourceTopics.of(source, partitionRefreshInterval = 1.seconds).partitionRefreshInterval
            .assert().isEqualTo(1.seconds)
        SourceTopics.of(source).partitionRefreshInterval
            .assert().isEqualTo(SourceTopics.DEFAULT_PARTITION_REFRESH_INTERVAL)
    }
}
