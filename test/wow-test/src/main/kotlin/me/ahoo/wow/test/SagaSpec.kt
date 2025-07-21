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

import me.ahoo.wow.test.dsl.AbstractDynamicTestBuilder
import me.ahoo.wow.test.saga.stateless.dsl.DefaultStatelessSagaDsl
import me.ahoo.wow.test.saga.stateless.dsl.StatelessSagaDsl
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.TestFactory
import java.lang.reflect.ParameterizedType
import java.util.stream.Stream

abstract class SagaSpec<T : Any>(private val block: StatelessSagaDsl<T>.() -> Unit) : AbstractDynamicTestBuilder() {
    val processorType: Class<T>
        get() {
            val type = this::class.java.genericSuperclass as ParameterizedType
            @Suppress("UNCHECKED_CAST")
            return type.actualTypeArguments[0] as Class<T>
        }

    @TestFactory
    fun execute(): Stream<DynamicNode> {
        val statelessSagaDsl = DefaultStatelessSagaDsl<T>(processorType)
        block(statelessSagaDsl)
        return statelessSagaDsl.dynamicNodes.stream()
    }
}
