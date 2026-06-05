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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.toNamedAggregate
import org.junit.jupiter.api.Test

internal class AggregateIdGeneratorRegistrarBehaviorTest {

    @Test
    fun `getOrInitialize should cache generator by materialized named aggregate`() {
        val namedAggregate = "task8.Order".toNamedAggregate()

        val generator = AggregateIdGeneratorRegistrar.getOrInitialize(namedAggregate)

        AggregateIdGeneratorRegistrar.containsKey(namedAggregate).assert().isTrue()
        AggregateIdGeneratorRegistrar.get(namedAggregate).assert().isSameAs(generator)
        AggregateIdGeneratorRegistrar.getOrInitialize(namedAggregate).assert().isSameAs(generator)
    }

    @Test
    fun `generateId extension should produce non blank aggregate id`() {
        "task8.Invoice".toNamedAggregate().generateId().assert().isNotBlank()
    }
}
