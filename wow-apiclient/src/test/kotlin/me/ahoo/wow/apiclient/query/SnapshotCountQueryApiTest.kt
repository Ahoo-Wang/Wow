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

package me.ahoo.wow.apiclient.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IdFilter
import org.junit.jupiter.api.Test

class SnapshotCountQueryApiTest {
    @Suppress("DEPRECATION")
    @Test
    fun `legacy count should delegate once to a filter implementation`() {
        lateinit var captured: FilterExpression
        val api = object : SnapshotCountQueryApi<Long> {
            override fun count(filter: FilterExpression): Long {
                captured = filter
                return 1
            }
        }

        api.count(Condition.id("id-1")).assert().isEqualTo(1)
        captured.assert().isEqualTo(IdFilter("id-1"))
    }
}
