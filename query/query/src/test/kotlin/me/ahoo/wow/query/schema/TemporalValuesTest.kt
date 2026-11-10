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
package me.ahoo.wow.query.schema

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

class TemporalValuesTest {
    private fun epoch(unit: TimeUnit) = Temporal.Epoch(unit)

    @Test
    fun `an epoch reads as its exact instant in every unit, past the nanoseconds a long holds`() {
        val year2500 = Instant.parse("2500-05-06T12:34:56Z")
        epoch(TimeUnit.MILLISECONDS).instantOf(year2500.toEpochMilli()).assert().isEqualTo(year2500)
        epoch(TimeUnit.SECONDS).instantOf(year2500.epochSecond).assert().isEqualTo(year2500)
        epoch(TimeUnit.DAYS).instantOf(1).assert().isEqualTo(Instant.parse("1970-01-02T00:00:00Z"))
        epoch(TimeUnit.NANOSECONDS).instantOf(Long.MAX_VALUE).assert()
            .isEqualTo(Instant.parse("2262-04-11T23:47:16.854775807Z"))
        epoch(TimeUnit.MICROSECONDS).instantOf(1_500_001).assert().isEqualTo(Instant.ofEpochSecond(1, 500_001_000))
    }

    @Test
    fun `an epoch before 1970 floors its fraction`() {
        epoch(TimeUnit.MICROSECONDS).instantOf(-1).assert().isEqualTo(Instant.ofEpochSecond(-1, 999_999_000))
        epoch(TimeUnit.NANOSECONDS).instantOf(-1).assert().isEqualTo(Instant.ofEpochSecond(-1, 999_999_999))
        epoch(TimeUnit.MICROSECONDS).instantOf(-1)!!.toEpochMilli().assert().isEqualTo(-1L)
        epoch(TimeUnit.MILLISECONDS).instantOf(-43_200_000).assert().isEqualTo(Instant.parse("1969-12-31T12:00:00Z"))
    }

    @Test
    fun `an epoch beyond the instants Java holds is none`() {
        epoch(TimeUnit.SECONDS).instantOf(Long.MAX_VALUE).assert().isNull()
        epoch(TimeUnit.DAYS).instantOf(Long.MAX_VALUE).assert().isNull()
        Instant.MAX.epochMilliOrNull().assert().isNull()
        Instant.EPOCH.epochMilliOrNull().assert().isEqualTo(0L)
    }

    @Test
    fun `an instant back to an epoch is floored to the unit, and none past a long`() {
        val instant = Instant.ofEpochSecond(-1, 999_999_000)
        epoch(TimeUnit.MICROSECONDS).epochOf(instant).assert().isEqualTo(-1L)
        epoch(TimeUnit.MILLISECONDS).epochOf(instant).assert().isEqualTo(-1L)
        epoch(TimeUnit.SECONDS).epochOf(instant).assert().isEqualTo(-1L)
        epoch(TimeUnit.DAYS).epochOf(Instant.parse("1969-12-31T12:00:00Z")).assert().isEqualTo(-1L)
        epoch(TimeUnit.NANOSECONDS).epochOf(Instant.parse("2500-01-01T00:00:00Z")).assert().isNull()
        epoch(TimeUnit.MILLISECONDS).epochOf(Instant.MIN).assert().isNull()
    }
}
