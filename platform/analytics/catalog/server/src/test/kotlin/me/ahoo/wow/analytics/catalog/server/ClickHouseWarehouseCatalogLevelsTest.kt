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

package me.ahoo.wow.analytics.catalog.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import org.junit.jupiter.api.Test

/** How the catalog reads a `wow_catalog` row's sensitivity level, which a Sink of any version wrote. */
class ClickHouseWarehouseCatalogLevelsTest {
    @Test
    fun `a level should be read by its wire name, and one this version does not know as confidential, warned once`() {
        ClickHouseWarehouseCatalog.levelOf("state.phone", "display").assert().isEqualTo(SensitivityLevel.DISPLAY)
        ClickHouseWarehouseCatalog.levelOf("state.phone", "confidential").assert()
            .isEqualTo(SensitivityLevel.CONFIDENTIAL)
        // A later version's level, and a pre-release row's constant name: the strongest this version enforces.
        listOf("later", "DISPLAY").forEach { level ->
            ClickHouseWarehouseCatalog.levelOf("state.phone", level).assert().isEqualTo(SensitivityLevel.CONFIDENTIAL)
        }
        ClickHouseWarehouseCatalog.unknownLevels.assert().contains("later", "DISPLAY")
    }
}
