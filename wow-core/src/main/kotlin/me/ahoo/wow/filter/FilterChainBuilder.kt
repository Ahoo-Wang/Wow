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
 * 定义一个函数式接口，用于确定是否应用过滤条件。
 * 这个接口的主要作用是提供一个标准方法来检查给定的过滤器是否满足某个条件。
 */
fun interface FilterCondition {
    /**
     * 检查给定的过滤器是否满足此条件。
     *
     * @param filter 要检查的过滤器对象。
     * @return 如果过滤器满足此条件，则返回true；否则返回false。
     */
    fun matches(filter: Filter<*>): Boolean

    /**
     * 伴生对象，用于定义一些预设的过滤条件。
     */
    companion object {
        /**
         * 一个特殊的过滤条件，表示所有过滤器都满足此条件。
         * 这个对象覆盖了matches方法，使其总是返回true。
         */
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

/**
 * 根据过滤器的类型执行过滤条件的类
 * 这个类用于检查给定的过滤器是否属于特定的类型或其子类型
 * 主要用于在复杂的过滤逻辑中快速识别和处理特定类型的过滤器
 *
 * @param filterType 指定的过滤器类型，用于匹配过滤器条件
 */
class TypedFilterCondition(private val filterType: KClass<*>) :
    FilterCondition {
    /**
     * 检查给定的过滤器是否匹配指定的类型条件
     * 通过反射获取过滤器的类型注解，然后检查该注解中是否包含指定的过滤器类型
     * 如果过滤器没有使用FilterType注解，则默认认为它匹配条件
     *
     * @param filter 待检查的过滤器对象
     * @return 如果过滤器匹配指定的类型条件，则返回true；否则返回false
     */
    override fun matches(filter: Filter<*>): Boolean {
        val type = filter::class.scanAnnotation<FilterType>()
        return type?.value?.contains(filterType) ?: true
    }

    /**
     * 生成描述当前过滤条件的字符串表示
     * 主要用于调试和日志记录，提供当前过滤条件的详细信息
     *
     * @return 描述当前过滤条件的字符串
     */
    override fun toString(): String {
        return "TypedFilterCondition(filterType=$filterType)"
    }
}

/**
 * 构建过滤链的类，用于灵活地组合多个过滤器
 * @param T 过滤链中过滤器处理的数据类型
 */
class FilterChainBuilder<T> {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * 过滤条件，默认为 ALL，即不过滤
     */
    private var filterCondition: FilterCondition = FilterCondition.ALL

    /**
     * 过滤器列表，存储待构建的过滤器
     */
    private val filters = mutableListOf<Filter<T>>()

    /**
     * 过滤链工厂方法，用于创建过滤链，默认为 SimpleFilterChain
     */
    private var chainFactory: (Filter<T>, FilterChain<T>) -> FilterChain<T> =
        { current, next ->
            SimpleFilterChain(current, next)
        }

    /**
     * 添加多个过滤器到过滤链中
     * @param filters 过滤器列表
     * @return FilterChainBuilder 实例，支持链式调用
     */
    fun addFilters(filters: List<Filter<T>>): FilterChainBuilder<T> {
        this.filters.addAll(filters)
        return this
    }

    /**
     * 添加单个过滤器到过滤链中
     * @param filter 过滤器
     * @return FilterChainBuilder 实例，支持链式调用
     */
    fun addFilter(filter: Filter<T>): FilterChainBuilder<T> {
        filters.add(filter)
        return this
    }

    /**
     * 设置过滤链工厂方法，自定义过滤链创建逻辑
     * @param chainFactory 过滤链工厂方法
     * @return FilterChainBuilder 实例，支持链式调用
     */
    fun chainFactory(chainFactory: (Filter<T>, FilterChain<T>) -> FilterChain<T>): FilterChainBuilder<T> {
        this.chainFactory = chainFactory
        return this
    }

    /**
     * 设置过滤条件
     * @param filterCondition 过滤条件
     * @return FilterChainBuilder 实例，支持链式调用
     */
    fun filterCondition(filterCondition: FilterCondition): FilterChainBuilder<T> {
        this.filterCondition = filterCondition
        return this
    }

    /**
     * 根据类型设置过滤条件
     * @param filterType 过滤器类型
     * @return FilterChainBuilder 实例，支持链式调用
     */
    fun filterCondition(filterType: KClass<*>): FilterChainBuilder<T> {
        return filterCondition(TypedFilterCondition(filterType))
    }

    /**
     * 构建过滤链
     * 根据设定的过滤条件和过滤器列表，创建一个过滤链实例
     * @return FilterChain<T> 过滤链实例
     */
    fun build(): FilterChain<T> {
        /**
         * 根据过滤条件筛选并排序过滤器
         */
        val sortedFilters = filters
            .filter { filterCondition.matches(it) }
            .sortedByOrder()

        /**
         * 从最后一个过滤器开始，逐步构建过滤链
         */
        var next: FilterChain<T> = EmptyFilterChain.instance()
        for (i in sortedFilters.size - 1 downTo 0) {
            next = chainFactory(sortedFilters[i], next)
        }
        /**
         * 记录过滤链构建信息
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
