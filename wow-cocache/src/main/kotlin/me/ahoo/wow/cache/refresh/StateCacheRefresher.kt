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

package me.ahoo.wow.cache.refresh

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.NamedAggregateDecorator
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.modeling.materialize
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/**
 * 主动刷新缓存.
 */
@JvmDefaultWithoutCompatibility
abstract class StateCacheRefresher<S : Any, D, M : DomainEventExchange<*>>(
    final override val namedAggregate: NamedAggregate
) :
    NamedAggregateDecorator,
    MessageFunction<StateCacheRefresher<S, D, M>, M, Mono<Void>> {
    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(StateCacheRefresher::class.java)
    }

    override val name: String = StateCacheRefresher<*, *, *>::invoke.name
    override val processor: StateCacheRefresher<S, D, M>
        get() = this
    final override val supportedTopics: Set<NamedAggregate> = setOf(namedAggregate.materialize())
    override val supportedType: Class<*> = Any::class.java

    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? {
        return null
    }

    override fun invoke(exchange: M): Mono<Void> {
        return Mono.fromRunnable<Void> {
            if (log.isDebugEnabled) {
                log.debug("[${this.javaClass.simpleName}] Refresh {} Cache.", exchange.message.aggregateId)
            }
            refresh(exchange)
        }.subscribeOn(Schedulers.boundedElastic())
    }

    abstract fun refresh(exchange: M)
}
