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

package me.ahoo.wow.test.aggregate.dsl

import me.ahoo.wow.test.aggregate.ExpectStage

/**
 * Context interface for managing shared state across aggregate DSL test stages.
 *
 * This interface provides a mechanism to store and retrieve ExpectStage instances by reference,
 * enabling complex test scenarios that need to branch or reference previous test states.
 * It acts as a registry for verified test stages that can be reused in forked test scenarios.
 *
 * Performance characteristics:
 * - Storage: O(1) hash map operations
 * - Retrieval: O(1) hash map operations
 * - Memory: Minimal overhead per reference (typically < 1KB per ref)
 * - Suitable for test scenarios with reasonable number of refs (< 100)
 *
 * @param S the state type of the aggregate being tested
 */
interface AggregateDslContext<S : Any> {
    val expectStages: MutableMap<String, ExpectStage<S>>

    fun setExpectStage(
        ref: String,
        expectStage: ExpectStage<S>
    )

    fun getExpectStage(ref: String): ExpectStage<S>

    /**
     * Returns a set of all available reference names.
     *
     * This method is useful for debugging and validation purposes,
     * allowing users to see what references are currently available.
     *
     * @return a set of all reference names that have been registered
     */
    fun getAvailableRefs(): Set<String> = expectStages.keys
}

/**
 * Default implementation of AggregateDslContext using a mutable map for storage.
 *
 * This class provides a concrete implementation of the AggregateDslContext interface,
 * using a standard mutable map to store ExpectStage references. It can be initialized
 * with an existing map or will create a new empty map by default.
 *
 * @param S the state type of the aggregate being tested
 * @property expectStages the mutable map used to store ExpectStage instances (defaults to empty map)
 */
class DefaultAggregateDslContext<S : Any>(
    override val expectStages: MutableMap<String, ExpectStage<S>> = mutableMapOf()
) : AggregateDslContext<S> {
    override fun setExpectStage(
        ref: String,
        expectStage: ExpectStage<S>
    ) {
        if (ref.isBlank()) {
            return
        }
        require(!expectStages.containsKey(ref)) {
            "Reference '$ref' is already in use. Each reference must be unique within a test scenario. " +
                "Consider using a different name or removing the previous reference."
        }
        expectStages[ref] = expectStage
    }

    override fun getExpectStage(ref: String): ExpectStage<S> {
        require(ref.isNotBlank()) {
            "Reference name cannot be blank. Use ref() method to mark test points before forking."
        }
        return requireNotNull(expectStages[ref]) {
            "No ExpectStage found for reference '$ref'. " +
                "Available references: ${expectStages.keys.joinToString(", ") { "'$it'" }}. " +
                "Make sure to call ref('$ref') at the desired test point before using fork()."
        }
    }
}

/**
 * Interface for components that provide access to an AggregateDslContext.
 *
 * This interface is implemented by DSL components that need to share or access
 * the aggregate DSL context for managing test stage references and state.
 *
 * @param S the state type of the aggregate being tested
 */
interface AggregateDslContextCapable<S : Any> {
    /**
     * The aggregate DSL context for managing shared test state.
     *
     * This property provides access to the context that stores ExpectStage references
     * and enables complex test scenarios with branching and state sharing.
     */
    val context: AggregateDslContext<S>
}
