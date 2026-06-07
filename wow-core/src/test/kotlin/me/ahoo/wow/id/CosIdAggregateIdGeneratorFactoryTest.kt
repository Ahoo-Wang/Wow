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

package me.ahoo.wow.id

import io.mockk.mockk
import me.ahoo.cosid.IdGenerator
import me.ahoo.cosid.cosid.ClockSyncCosIdGenerator
import me.ahoo.cosid.provider.DefaultIdGeneratorProvider
import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.toNamedAggregate
import org.junit.jupiter.api.Test

internal class CosIdAggregateIdGeneratorFactoryTest {

    @Test
    fun `create should prefer provider generator named by aggregate name`() {
        val provider = DefaultIdGeneratorProvider()
        val generator = mockk<IdGenerator>()
        provider.set("Order", generator)

        CosIdAggregateIdGeneratorFactory(provider).create("sales.Order".toNamedAggregate())
            .assert().isSameAs(generator)
    }

    @Test
    fun `create should fall back to clock synchronized CosId generator`() {
        val generator = CosIdAggregateIdGeneratorFactory(DefaultIdGeneratorProvider())
            .create("sales.Order".toNamedAggregate())

        generator.assert().isInstanceOf(ClockSyncCosIdGenerator::class.java)
        (generator as ClockSyncCosIdGenerator).machineId.assert().isEqualTo(GlobalIdGenerator.machineId)
    }
}
