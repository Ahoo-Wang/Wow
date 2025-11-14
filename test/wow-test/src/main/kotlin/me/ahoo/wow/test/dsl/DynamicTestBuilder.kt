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

package me.ahoo.wow.test.dsl

import org.junit.jupiter.api.DynamicNode

/**
 * Interface for building dynamic test nodes in JUnit 5.
 *
 * Implementations of this interface provide a list of [DynamicNode] instances
 * that can be used to create dynamic tests at runtime.
 *
 * @property dynamicNodes A list of dynamic test nodes to be executed.
 * @see DynamicNode
 */
interface DynamicTestBuilder {
    val dynamicNodes: List<DynamicNode>
}

/**
 * Abstract base implementation of [DynamicTestBuilder] that provides a mutable list for dynamic nodes.
 *
 * This class can be extended to build custom dynamic test builders that accumulate
 * [DynamicNode] instances during test construction.
 *
 * @property dynamicNodes A mutable list of dynamic test nodes that can be modified by subclasses.
 */
abstract class AbstractDynamicTestBuilder : DynamicTestBuilder {
    override val dynamicNodes: MutableList<DynamicNode> = mutableListOf()
}
