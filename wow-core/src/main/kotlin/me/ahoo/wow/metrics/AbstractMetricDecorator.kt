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

package me.ahoo.wow.metrics

import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.infra.Decorator.Companion.getOriginalDelegate
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Abstract base class for metric decorators that provides common functionality for tagging
 * reactive streams with source information. This class implements the Decorator pattern to wrap
 * components and add metrics collection capabilities.
 *
 * @param T the type of the delegate component being decorated
 * @property delegate the original component being decorated, must implement the Decorator interface
 */
abstract class AbstractMetricDecorator<T : Any>(
    final override val delegate: T
) : Decorator<T> {
    /**
     * The source identifier derived from the original delegate's class name.
     * This is used for metrics tagging to identify the component type.
     */
    val source: String = delegate.getOriginalDelegate().javaClass.simpleName

    /**
     * Extension function to tag a Mono publisher with the current source identifier.
     * This adds the source tag to metrics collected from this reactive stream.
     *
     * @param M the type of elements in the Mono
     * @return the tagged Mono publisher
     */
    fun <M : Any> Mono<M>.tagSource(): Mono<M> = this.tagSource(source)

    /**
     * Extension function to tag a Flux publisher with the current source identifier.
     * This adds the source tag to metrics collected from this reactive stream.
     *
     * @param M the type of elements in the Flux
     * @return the tagged Flux publisher
     */
    fun <M : Any> Flux<M>.tagSource(): Flux<M> = this.tagSource(source)

    companion object {
        /**
         * Static extension function to tag a Mono publisher with a specified source identifier.
         * This is used for metrics tagging to identify the component that generated the metrics.
         *
         * @param M the type of elements in the Mono
         * @param source the source identifier to tag the metrics with
         * @return the tagged Mono publisher
         */
        fun <M : Any> Mono<M>.tagSource(source: String): Mono<M> = this.tag(Metrics.SOURCE_KEY, source)

        /**
         * Static extension function to tag a Flux publisher with a specified source identifier.
         * This is used for metrics tagging to identify the component that generated the metrics.
         *
         * @param M the type of elements in the Flux
         * @param source the source identifier to tag the metrics with
         * @return the tagged Flux publisher
         */
        fun <M : Any> Flux<M>.tagSource(source: String): Flux<M> = this.tag(Metrics.SOURCE_KEY, source)
    }
}
