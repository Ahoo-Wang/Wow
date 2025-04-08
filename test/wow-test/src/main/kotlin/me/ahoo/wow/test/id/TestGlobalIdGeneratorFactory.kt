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

package me.ahoo.wow.test.id

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosid.cosid.ClockSyncCosIdGenerator
import me.ahoo.cosid.cosid.CosIdGenerator
import me.ahoo.cosid.cosid.Radix62CosIdGenerator
import me.ahoo.wow.api.annotation.ORDER_LAST
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.id.GlobalIdGeneratorFactory

@Order(ORDER_LAST)
class TestGlobalIdGeneratorFactory : GlobalIdGeneratorFactory {
    companion object {
        private val log = KotlinLogging.logger { }
        private const val TEST_MACHINE_ID: Int = 1048575
    }

    override fun create(): CosIdGenerator {
        val idGenerator = Radix62CosIdGenerator(TEST_MACHINE_ID)
        val clockSyncCosIdGenerator = ClockSyncCosIdGenerator(idGenerator)
        log.info {
            "Create - [$clockSyncCosIdGenerator]."
        }
        return clockSyncCosIdGenerator
    }
}
