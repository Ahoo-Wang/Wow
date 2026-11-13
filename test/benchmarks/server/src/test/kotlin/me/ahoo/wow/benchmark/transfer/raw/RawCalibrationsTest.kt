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

package me.ahoo.wow.benchmark.transfer.raw

import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class RawCalibrationsTest {
    private fun step(storeCpuMsPerUnit: Double?) = RawStep(
        case = RawCase("wow-today", "command", 10, "today"),
        connections = 4,
        rate = null,
        seconds = 2.0,
        units = 1000,
        flushes = 100,
        failures = 0,
        refused = 0,
        late = 0,
        p50FlushMs = 1.5,
        p99FlushMs = 9.0,
        storeCpuMsPerUnit = storeCpuMsPerUnit,
        perUnit = emptyMap(),
    )

    private fun RawCalibration.apartOf(metric: String) = apart.single { it.metric == metric }

    @Test
    fun `mongod's time on wow's side is the log's inserts and the snapshots' updates apart from the rest, a command`() {
        val wow = mapOf(
            "transfer.${RawMongo.LOG} insert micros" to 22.6,
            "transfer.${RawMongo.SNAPSHOT} update micros" to 47.6,
            "transfer.${RawMongo.LOG} commands micros" to 9.7,
            "transfer.${RawMongo.COUNTERS} commands micros" to 3.8,
            "transfer.wow_checkpoint update micros" to 3.7,
            // Not an operation's: a namespace's total is left out, as top reports it beside its kinds.
            "transfer.${RawMongo.LOG} total micros" to 1000.0,
            "transfer.${RawMongo.LOG} insert" to 0.066,
        )
        val calibration = mongoCalibration(wow, step(null), step(null))
        calibration.apartOf("mongodWritesMs").wow!!.assert().isEqualTo(0.0702, within(1e-9))
        calibration.apartOf("mongodOtherMs").wow!!.assert().isEqualTo(0.0172, within(1e-9))
        calibration.apartOf("mongodWritesMs").raw.assert().isNull()
    }

    @Test
    fun `the store's CPU a command is reported apart on both sides, wow's from its counter's microseconds`() {
        val calibration = mongoCalibration(mapOf(STORE_CPU_MICROS to 154.0), step(0.091), step(null))
        calibration.apartOf(STORE_CPU_MS).wow!!.assert().isEqualTo(0.154, within(1e-9))
        calibration.apartOf(STORE_CPU_MS).raw!!.assert().isEqualTo(0.091, within(1e-9))
        val none = mongoCalibration(emptyMap(), step(null), step(null))
        none.apartOf(STORE_CPU_MS).wow.assert().isNull()
        none.apartOf("mongodWritesMs").wow.assert().isNull()
    }
}
