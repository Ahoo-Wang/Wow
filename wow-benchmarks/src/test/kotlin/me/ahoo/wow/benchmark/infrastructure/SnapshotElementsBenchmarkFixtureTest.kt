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

package me.ahoo.wow.benchmark.infrastructure

import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import org.junit.jupiter.api.Test

class SnapshotElementsBenchmarkFixtureTest {
    @Test
    fun `should resolve benchmark metadata and construct nested fixtures`() {
        val metadata = aggregateMetadata<SnapshotElementsBenchmarkAggregate, SnapshotElementsBenchmarkState>()
        metadata.state.aggregateType.assert().isEqualTo(SnapshotElementsBenchmarkState::class.java)

        val leaf = BenchmarkLeaf("key", 1, 2.0)
        val state = SnapshotElementsBenchmarkState("id").apply {
            items = listOf(BenchmarkItem("key", 1, 2.0))
            groups = listOf(BenchmarkFirstLevel(listOf(BenchmarkSecondLevel(listOf(leaf)))))
        }
        SnapshotElementsBenchmarkAggregate(state).state.groups.single().children.single().leaves.assert()
            .containsExactly(leaf)
    }
}
