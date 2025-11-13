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

package me.ahoo.wow.filter

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.annotation.sortedByOrder
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import kotlin.reflect.KClass

/**
 * Functional interface for determining whether to apply a filter condition.
 *
 * The main purpose of this interface is to provide a standard method to check if a given filter
 * meets a certain condition.
 */
fun interface FilterCondition {
    /**
     * Checks if the given filter meets this condition.
     *
     * @param filter the filter object to check
     * @return true if the filter meets this condition, false otherwise
     */
    fun matches(filter: Filter<*>): Boolean

    /**
     * Companion object for defining preset filter conditions.
     */
    companion object {
        /**
         * A special filter condition that indicates all filters meet this condition.
         * This object overrides the matches method to always return true.
         */
        val ALL: FilterCondition =
            object : FilterCondition {
                override fun matches(filter: Filter<*>): Boolean = true

                override fun toString(): String = "ALL"
            }
    }
}

/**
 * Class that executes filter conditions based on filter type.
 *
 * This class checks if a given filter belongs to a specific type or its subtypes.
 * Mainly used in complex filter logic to quickly identify and handle specific types of filters.
 *
 * @param filterType the specified filter type used for matching filter conditions
 */
class TypedFilterCondition(
    private val filterType: KClass<*>
) : FilterCondition {
    /**
     * Checks if the given filter matches the specified type condition.
     *
     * Uses reflection to get the filter's type annotation, then checks if the annotation contains the specified filter type.
     * If the filter does not use the FilterType annotation, it is considered to match by default.
     *
     * @param filter the filter object to check
     * @return true if the filter matches the specified type condition, false otherwise
     */
    override fun matches(filter: Filter<*>): Boolean {
        val type = filter::class.scanAnnotation<FilterType>()
        return type?.value?.contains(filterType) ?: true
    }

    /**
     * Generates a string representation describing the current filter condition.
     *
     * Mainly used for debugging and logging, providing detailed information about the current filter condition.
     *
     * @return a string describing the current filter condition
     */
    override fun toString(): String {
        return "TypedFilterCondition(filterType=$filterType)"
    }
}

/**
 * Class for building filter chains, used to flexibly combine multiple filters.
 *
 * @param T the data type processed by filters in the filter chain
 */
class FilterChainBuilder<T> {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * Filter condition, defaults to ALL, meaning no filtering.
     */
    private var filterCondition: FilterCondition = FilterCondition.ALL

    /**
     * List of filters to be built into the chain.
     */
    private val filters = mutableListOf<Filter<T>>()

    /**
     * Factory method for creating filter chains, defaults to SimpleFilterChain.
     */
    private var chainFactory: (Filter<T>, FilterChain<T>) -> FilterChain<T> =
        { current, next ->
            SimpleFilterChain(current, next)
        }

    /**
     * Adds multiple filters to the filter chain.
     *
     * @param filters the list of filters to add
     * @return the FilterChainBuilder instance, supporting method chaining
     */
    fun addFilters(filters: List<Filter<T>>): FilterChainBuilder<T> {
        this.filters.addAll(filters)
        return this
    }

    /**
     * Adds a single filter to the filter chain.
     *
     * @param filter the filter to add
     * @return the FilterChainBuilder instance, supporting method chaining
     */
    fun addFilter(filter: Filter<T>): FilterChainBuilder<T> {
        filters.add(filter)
        return this
    }

    /**
     * Sets the filter chain factory method to customize filter chain creation logic.
     *
     * @param chainFactory the filter chain factory method
     * @return the FilterChainBuilder instance, supporting method chaining
     */
    fun chainFactory(chainFactory: (Filter<T>, FilterChain<T>) -> FilterChain<T>): FilterChainBuilder<T> {
        this.chainFactory = chainFactory
        return this
    }

    /**
     * Sets the filter condition.
     *
     * @param filterCondition the filter condition
     * @return the FilterChainBuilder instance, supporting method chaining
     */
    fun filterCondition(filterCondition: FilterCondition): FilterChainBuilder<T> {
        this.filterCondition = filterCondition
        return this
    }

    /**
     * Sets the filter condition based on type.
     *
     * @param filterType the filter type
     * @return the FilterChainBuilder instance, supporting method chaining
     */
    fun filterCondition(filterType: KClass<*>): FilterChainBuilder<T> {
        return filterCondition(TypedFilterCondition(filterType))
    }

    /**
     * Builds the filter chain.
     *
     * Creates a filter chain instance based on the set filter conditions and filter list.
     *
     * @return the FilterChain<T> instance
     */
    fun build(): FilterChain<T> {
        /**
         * Filter and sort filters based on filter conditions.
         */
        val sortedFilters = filters
            .filter { filterCondition.matches(it) }
            .sortedByOrder()

        /**
         * Build the filter chain starting from the last filter.
         */
        var next: FilterChain<T> = EmptyFilterChain.instance()
        for (i in sortedFilters.size - 1 downTo 0) {
            next = chainFactory(sortedFilters[i], next)
        }
        /**
         * Log filter chain build information.
         */
        log.info {
            buildString {
                sortedFilters.forEachIndexed { index, filter ->
                    append(filter.javaClass.name)
                    if (index != sortedFilters.size - 1) {
                        append(" -> ")
                    }
                }
            }.let {
                "Build - Condition:[$filterCondition] - FilterChain: $it"
            }
        }
        return next
    }
}
