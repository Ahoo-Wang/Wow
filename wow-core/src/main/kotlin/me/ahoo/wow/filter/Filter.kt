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

import reactor.core.publisher.Mono
import java.lang.annotation.Inherited
import kotlin.reflect.KClass

/**
 * 定义一个注解，用于标记过滤器的类型
 * 该注解可以应用于其他注解类或类，且具有继承性
 *
 * @param value 一个或多个KClass类型参数，表示过滤器处理的类型
 */
@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS)
@Inherited
annotation class FilterType(
    vararg val value: KClass<*>
)

/**
 * 定义一个过滤器接口，用于实现自定义的过滤逻辑
 * 接口使用了泛型，允许在不同的上下文中使用不同的类型进行过滤
 *
 * @param T 过滤器处理的上下文类型
 */
fun interface Filter<T> {
    /**
     * 实现过滤逻辑的方法
     * 该方法接收一个上下文对象和一个过滤链对象，执行过滤逻辑后，必须调用过滤链的next方法继续执行链上的下一个过滤器
     *
     * @param context 当前过滤操作的上下文对象
     * @param next 过滤链对象，用于调用链上的下一个过滤器
     * @return 返回一个Mono<Void>，表示过滤操作完成
     */
    fun filter(context: T, next: FilterChain<T>): Mono<Void>
}
