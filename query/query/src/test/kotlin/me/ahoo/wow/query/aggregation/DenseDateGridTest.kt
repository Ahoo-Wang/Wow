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

package me.ahoo.wow.query.aggregation

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class DenseDateGridTest {
    @Test
    fun `the anchor is the local 1970 midnight, a Monday for weeks`() {
        val zone = ZoneId.of("Asia/Shanghai")
        DenseDateGrid(AggregationDateUnit.DAY, zone).anchor
            .assert().isEqualTo(ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, zone))
        DenseDateGrid(AggregationDateUnit.WEEK, zone).anchor.dayOfWeek.assert().isEqualTo(DayOfWeek.MONDAY)
    }
}
