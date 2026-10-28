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

package me.ahoo.wow.benchmark.load

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class ProcessTreeCpuTest {
    @Test
    fun `the tree's CPU should sum each process's growth, and the whole of one started in between`() {
        val before = mapOf(1L to 10.0, 2L to 3.0)
        val after = mapOf(1L to 12.5, 2L to 3.5, 3L to 0.75)
        ProcessTreeCpu.since(before, after).assert().isEqualTo(3.75)
    }

    @Test
    fun `a process that ended in between should add nothing, and none should count below zero`() {
        ProcessTreeCpu.since(mapOf(1L to 10.0, 2L to 3.0), mapOf(1L to 11.0)).assert().isEqualTo(1.0)
        // A pid reused by a new process reads less than the old one did: it adds nothing rather than a negative.
        ProcessTreeCpu.since(mapOf(1L to 10.0, 2L to 3.0), mapOf(1L to 11.0, 2L to 0.5)).assert().isEqualTo(1.0)
        ProcessTreeCpu.since(emptyMap(), emptyMap()).assert().isEqualTo(0.0)
    }

    @Test
    fun `the snapshot should hold this JVM and a process it started`() {
        val child = ProcessBuilder("sleep", "5").start()
        try {
            val snapshot = ProcessTreeCpu.snapshot()
            snapshot.keys.assert().contains(ProcessHandle.current().pid(), child.pid())
            snapshot.getValue(ProcessHandle.current().pid()).assert().isPositive()
        } finally {
            child.destroy()
        }
    }
}
