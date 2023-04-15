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

package me.ahoo.wow.messaging.handler

import me.ahoo.wow.annotation.OrderComparator
import me.ahoo.wow.infra.reflection.AnnotationScanner.scan
import me.ahoo.wow.messaging.MessageDispatcher
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

fun interface FilterCondition {
    fun matches(filter: Filter<*>): Boolean

    companion object {
        val ALL: FilterCondition = object : FilterCondition {
            override fun matches(filter: Filter<*>): Boolean {
                return true
            }

            override fun toString(): String {
                return "ALL"
            }
        }
    }
}

class TypedFilterCondition(private val filterType: KClass<*>) :
    FilterCondition {
    override fun matches(filter: Filter<*>): Boolean {
        val type = filter::class.java.scan<FilterType>()
        return type?.value?.contains(filterType) ?: true
    }

    override fun toString(): String {
        return "TypedFilterCondition(filterType=$filterType)"
    }
}

class FilterChainBuilder<T : MessageExchange<*>> {
    companion object {
        private val log = LoggerFactory.getLogger(FilterChainBuilder::class.java)
    }

    private var filterCondition: FilterCondition = FilterCondition.ALL
    private val filters = mutableListOf<Filter<T>>()
    private var chainFactory: (Filter<T>, FilterChain<T>) -> FilterChain<T> =
        { current, next ->
            SimpleFilterChain(current, next)
        }

    fun addFilters(filters: List<Filter<T>>): FilterChainBuilder<T> {
        this.filters.addAll(filters)
        return this
    }

    fun addFilter(filter: Filter<T>): FilterChainBuilder<T> {
        filters.add(filter)
        return this
    }

    fun chainFactory(chainFactory: (Filter<T>, FilterChain<T>) -> FilterChain<T>): FilterChainBuilder<T> {
        this.chainFactory = chainFactory
        return this
    }

    fun filterCondition(filterCondition: FilterCondition): FilterChainBuilder<T> {
        this.filterCondition = filterCondition
        return this
    }

    fun filterCondition(filterType: KClass<out MessageDispatcher>): FilterChainBuilder<T> {
        return filterCondition(TypedFilterCondition(filterType))
    }

    fun build(): FilterChain<T> {
        val sortedFilters = filters
            .filter { filterCondition.matches(it) }
            .sortedWith(OrderComparator)
        var next: FilterChain<T> = EmptyFilterChain.instance()
        for (i in sortedFilters.size - 1 downTo 0) {
            next = chainFactory(sortedFilters[i], next)
        }
        if (log.isInfoEnabled) {
            buildString {
                sortedFilters.forEachIndexed { index, filter ->
                    append(filter.javaClass.name)
                    if (index != sortedFilters.size - 1) {
                        append(" -> ")
                    }
                }
            }.let {
                log.info("Build - Condition:[{}] - FilterChain: {}", filterCondition, it)
            }
        }
        return next
    }
}
