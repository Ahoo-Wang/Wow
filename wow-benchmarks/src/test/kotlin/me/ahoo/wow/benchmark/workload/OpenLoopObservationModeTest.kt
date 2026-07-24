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

package me.ahoo.wow.benchmark.workload

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OpenLoopObservationModeTest {
    @Test
    fun `should keep every correctness and latency observer in full mode`() {
        val mode = OpenLoopObservationMode.FULL

        mode.deadlineWheelEnabled.assert().isTrue()
        mode.serverTrackerEnabled.assert().isTrue()
        mode.generatorLatencyEnabled.assert().isTrue()
        mode.serviceLatencyEnabled.assert().isTrue()
        mode.fullObservationCoverage.assert().isTrue()
    }

    @Test
    fun `should represent each diagnostic observer ablation`() {
        assertFlags(
            mode = OpenLoopObservationMode.NO_DEADLINE_WHEEL,
            deadlineWheelEnabled = false,
            serverTrackerEnabled = true,
            generatorLatencyEnabled = true,
            serviceLatencyEnabled = true,
        )
        assertFlags(
            mode = OpenLoopObservationMode.NO_SERVER_TRACKER,
            deadlineWheelEnabled = true,
            serverTrackerEnabled = false,
            generatorLatencyEnabled = true,
            serviceLatencyEnabled = true,
        )
        assertFlags(
            mode = OpenLoopObservationMode.GENERATOR_ONLY_LATENCY,
            deadlineWheelEnabled = true,
            serverTrackerEnabled = true,
            generatorLatencyEnabled = true,
            serviceLatencyEnabled = false,
        )
        assertFlags(
            mode = OpenLoopObservationMode.NO_LATENCY,
            deadlineWheelEnabled = true,
            serverTrackerEnabled = true,
            generatorLatencyEnabled = false,
            serviceLatencyEnabled = false,
        )
        OpenLoopObservationMode.entries
            .filterNot { it == OpenLoopObservationMode.FULL }
            .forEach { mode ->
                mode.fullObservationCoverage.assert().isFalse()
            }
    }

    @Test
    fun `should parse stable command line tokens`() {
        OpenLoopObservationMode.parse("FULL")
            .assert()
            .isEqualTo(OpenLoopObservationMode.FULL)
        OpenLoopObservationMode.parse("no-server-tracker")
            .assert()
            .isEqualTo(OpenLoopObservationMode.NO_SERVER_TRACKER)
        OpenLoopObservationMode.parse("generator_only_latency")
            .assert()
            .isEqualTo(OpenLoopObservationMode.GENERATOR_ONLY_LATENCY)
    }

    @Test
    fun `should reject an unsupported command line token`() {
        assertThrows<IllegalArgumentException> {
            OpenLoopObservationMode.parse("partial")
        }.message.assert().contains("Unsupported observationMode")
    }

    private fun assertFlags(
        mode: OpenLoopObservationMode,
        deadlineWheelEnabled: Boolean,
        serverTrackerEnabled: Boolean,
        generatorLatencyEnabled: Boolean,
        serviceLatencyEnabled: Boolean,
    ) {
        mode.deadlineWheelEnabled.assert().isEqualTo(deadlineWheelEnabled)
        mode.serverTrackerEnabled.assert().isEqualTo(serverTrackerEnabled)
        mode.generatorLatencyEnabled.assert().isEqualTo(generatorLatencyEnabled)
        mode.serviceLatencyEnabled.assert().isEqualTo(serviceLatencyEnabled)
    }
}
