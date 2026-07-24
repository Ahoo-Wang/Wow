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

class OpenLoopOutcomeSnapshotTest {
    @Test
    fun `should accept a conserved terminal snapshot`() {
        val snapshot = OpenLoopOutcomeSnapshot(
            planned = 100,
            generatorMissed = 3,
            generatorExpired = 2,
            shedAtMaxInFlight = 5,
            admitted = 90,
            sent = 89,
            sentByDeadline = 88,
            processedByDeadline = 80,
            commandFailed = 2,
            gatewayFailed = 1,
            timedOut = 7,
            forcedCancelled = 0,
        )

        snapshot.terminal.assert().isEqualTo(90)
        snapshot.invariantViolations().assert().isEmpty()
    }

    @Test
    fun `should report every violated conservation boundary`() {
        val snapshot = OpenLoopOutcomeSnapshot(
            planned = 10,
            generatorMissed = 0,
            generatorExpired = 0,
            shedAtMaxInFlight = 0,
            admitted = 9,
            sent = 10,
            sentByDeadline = 11,
            processedByDeadline = 12,
            commandFailed = 0,
            gatewayFailed = 0,
            timedOut = 0,
            forcedCancelled = 0,
        )

        snapshot.invariantViolations()
            .assert()
            .containsExactly(
                "planned[10] != generatorMissed + generatorExpired + shedAtMaxInFlight + admitted[9]",
                "admitted[9] != terminal[12]",
                "sent[10] > admitted[9]",
                "sentByDeadline[11] > sent[10]",
            )
    }

    @Test
    fun `should allow processed observation to precede sent completion observation`() {
        val snapshot = OpenLoopOutcomeSnapshot(
            planned = 1,
            generatorMissed = 0,
            generatorExpired = 0,
            shedAtMaxInFlight = 0,
            admitted = 1,
            sent = 1,
            sentByDeadline = 0,
            processedByDeadline = 1,
            commandFailed = 0,
            gatewayFailed = 0,
            timedOut = 0,
            forcedCancelled = 0,
        )

        snapshot.invariantViolations().assert().isEmpty()
    }

    @Test
    fun `should accept generator fidelity exactly at configured boundaries`() {
        val snapshot = generatorSnapshot(
            planned = 1_000,
            generatorMissed = 1,
        )

        snapshot.generatorFidelityViolations(
            generatorLagP99Micros = 5_000.0,
            maxMissedRatio = 0.001,
            maxLagP99Micros = 5_000.0,
        ).assert().isEmpty()
    }

    @Test
    fun `should report missed ratio and lag beyond configured boundaries`() {
        val snapshot = generatorSnapshot(
            planned = 1_000,
            generatorMissed = 2,
        )

        snapshot.generatorFidelityViolations(
            generatorLagP99Micros = 5_000.1,
            maxMissedRatio = 0.001,
            maxLagP99Micros = 5_000.0,
        ).assert().containsExactly(
            "measurement generatorMissedRatio[0.002] > maxGeneratorMissedRatio[0.001]",
            "measurement generatorLagP99Micros[5000.1] > maxGeneratorLagP99Micros[5000.0]",
        )
    }

    @Test
    fun `should reject generator arrivals that expired before admission`() {
        val snapshot = generatorSnapshot(
            planned = 1_000,
            generatorExpired = 1,
        )

        snapshot.generatorFidelityViolations(
            generatorLagP99Micros = 1_000.0,
            maxMissedRatio = 0.001,
            maxLagP99Micros = 5_000.0,
        ).assert().containsExactly(
            "measurement generatorExpired[1] != 0"
        )
    }

    @Test
    fun `should reject a run without an admitted generator lag sample`() {
        val snapshot = generatorSnapshot(
            planned = 1,
            generatorMissed = 1,
        )

        snapshot.generatorFidelityViolations(
            generatorLagP99Micros = null,
            maxMissedRatio = 1.0,
            maxLagP99Micros = 5_000.0,
        ).assert().containsExactly(
            "measurement generatorLagP99Micros is unavailable"
        )
    }

    @Test
    fun `should preserve arrival fidelity checks when latency sampling is diagnostic-disabled`() {
        val snapshot = generatorSnapshot(
            planned = 1_000,
            generatorMissed = 2,
            generatorExpired = 1,
        )

        snapshot.generatorFidelityViolations(
            generatorLagP99Micros = null,
            maxMissedRatio = 0.001,
            maxLagP99Micros = 5_000.0,
            requireLagSample = false,
        ).assert().containsExactly(
            "measurement generatorExpired[1] != 0",
            "measurement generatorMissedRatio[0.002] > maxGeneratorMissedRatio[0.001]",
        )
    }

    private fun generatorSnapshot(
        planned: Long,
        generatorMissed: Long = 0,
        generatorExpired: Long = 0,
    ): OpenLoopOutcomeSnapshot {
        val admitted = planned - generatorMissed - generatorExpired
        return OpenLoopOutcomeSnapshot(
            planned = planned,
            generatorMissed = generatorMissed,
            generatorExpired = generatorExpired,
            shedAtMaxInFlight = 0,
            admitted = admitted,
            sent = admitted,
            sentByDeadline = admitted,
            processedByDeadline = admitted,
            commandFailed = 0,
            gatewayFailed = 0,
            timedOut = 0,
            forcedCancelled = 0,
        )
    }
}
