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

abstract class AbstractMetricDecorator<T : Any>(final override val delegate: T) : Decorator<T> {
    val source: String = delegate.getOriginalDelegate().javaClass.simpleName

    fun <M> Mono<M>.tagSource(): Mono<M> {
        return this.tagSource(source)
    }

    fun <M> Flux<M>.tagSource(): Flux<M> {
        return this.tagSource(source)
    }

    companion object {
        fun <M> Mono<M>.tagSource(source: String): Mono<M> {
            return this.tag(Metrics.SOURCE_KEY, source)
        }

        fun <M> Flux<M>.tagSource(source: String): Flux<M> {
            return this.tag(Metrics.SOURCE_KEY, source)
        }
    }
}
