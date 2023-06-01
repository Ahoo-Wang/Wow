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

package me.ahoo.wow.sharding

import me.ahoo.cosid.sharding.ModCycle
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.asAggregateId
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

internal class CosIdShardingDecoratorTest {
    private val namedAggregate = MaterializedNamedAggregate("test", "test")

    private val divisor = 4
    private val sharding = CosIdShardingDecorator(ModCycle(divisor, "sharding_"))

    @ParameterizedTest
    @CsvSource(
        value = [
            "0TEDamtj0001001,1",
            "0TEDamtj0001002,2",
            "0TEDamtj0001003,3",
            "0TEDamtj0001004,4",
            "0TEDamtj0001005,5",
        ],
    )
    fun sharding(aggregateId: String, shardingValue: Int) {
        val actual = sharding.sharding(namedAggregate.asAggregateId(aggregateId))
        assertThat(actual, equalTo("sharding_${shardingValue % divisor}"))
    }
}
