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

package me.ahoo.wow.test

import me.ahoo.wow.test.aggregate.dsl.AggregateDsl
import me.ahoo.wow.test.aggregate.dsl.DefaultAggregateDsl
import me.ahoo.wow.test.dsl.AbstractDynamicTestBuilder
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.TestFactory
import java.lang.reflect.ParameterizedType
import java.util.stream.Stream

abstract class AggregateSpec<C : Any, S : Any>(val block: AggregateDsl<S>.() -> Unit) :
    AbstractDynamicTestBuilder() {
    val commandAggregateType: Class<C>
        get() {
            val type = this::class.java.genericSuperclass as ParameterizedType
            @Suppress("UNCHECKED_CAST")
            return type.actualTypeArguments[0] as Class<C>
        }

    @TestFactory
    fun execute(): Stream<DynamicNode> {
        val aggregateDsl = DefaultAggregateDsl<C, S>(commandAggregateType)
        block(aggregateDsl)
        return aggregateDsl.dynamicNodes.stream()
    }
}
