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

package me.ahoo.wow.spring.boot

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The host maps its query properties onto wow-query's bounds (review R5.C-2), key by key. */
class QueryStoreSettingsTest {
    @Test
    fun `the global, per-store and dataset bounds should map onto the store settings`() {
        val properties = QueryProperties().apply {
            maxConcurrent = 12
            maxWait = Duration.ofSeconds(2)
            maxQueued = 30
            adaptive.enabled = false
            adaptive.ceiling = 64
            dataset.maxConcurrent = 3
            dataset.maxWait = Duration.ofSeconds(4)
            dataset.maxQueued = 9
        }
        val store = WowProperties.Store().apply {
            query.maxConcurrent = 5
            query.maxWait = Duration.ofSeconds(1)
            query.maxQueued = 7
            query.adaptive.enabled = true
            query.adaptive.floor = 2
            query.adaptive.ceiling = 20
        }
        val settings = properties.storeSettings(mapOf("main" to store, "plain" to WowProperties.Store()))
        settings.maxConcurrent.assert().isEqualTo(12)
        settings.maxWait.assert().isEqualTo(2.seconds)
        settings.maxQueued.assert().isEqualTo(30)
        settings.adaptiveEnabled.assert().isFalse()
        settings.adaptiveCeiling.assert().isEqualTo(64)
        val main = settings.stores.getValue("main")
        main.maxConcurrent.assert().isEqualTo(5)
        main.maxWait.assert().isEqualTo(1.seconds)
        main.maxQueued.assert().isEqualTo(7)
        main.adaptive.assert().isEqualTo(true)
        main.floor.assert().isEqualTo(2)
        main.ceiling.assert().isEqualTo(20)
        val plain = settings.stores.getValue("plain")
        plain.maxConcurrent.assert().isNull()
        plain.maxWait.assert().isNull()
        plain.adaptive.assert().isNull()
        settings.datasets.maxConcurrent.assert().isEqualTo(3)
        settings.datasets.maxWait.assert().isEqualTo(4.seconds)
        settings.datasets.maxQueued.assert().isEqualTo(9)
        QueryProperties().storeSettings(emptyMap()).datasets.maxWait.assert().isNull()
    }
}
