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

package me.ahoo.wow.query.backend

import me.ahoo.wow.api.query.gateway.QueryConsistency
import me.ahoo.wow.api.query.gateway.QueryPage
import me.ahoo.wow.query.plan.CountQueryPlanV1
import me.ahoo.wow.query.plan.ListQueryPlanV1
import me.ahoo.wow.query.plan.PageQueryPlanV1
import me.ahoo.wow.query.plan.SingleQueryPlanV1
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.SignalType
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal class RecordingQueryBackend(
    private val initialDescriptor: QueryBackendDescriptor,
    private var readinessPublisher: Mono<QueryBackendReadiness> = Mono.just(QueryBackendReadiness.Ready),
    private val descriptorProvider: (() -> QueryBackendDescriptor)? = null
) : QueryBackend {
    override val descriptor: QueryBackendDescriptor
        get() = descriptorProvider?.invoke() ?: initialDescriptor
    val readinessSubscriptions: AtomicInteger = AtomicInteger()
    val singleSubscriptions: AtomicInteger = AtomicInteger()
    val listSubscriptions: AtomicInteger = AtomicInteger()
    val pageSubscriptions: AtomicInteger = AtomicInteger()
    val countSubscriptions: AtomicInteger = AtomicInteger()
    val cancellations: AtomicInteger = AtomicInteger()
    val terminals: AtomicInteger = AtomicInteger()
    val singlePlans: MutableList<SingleQueryPlanV1<*>> = CopyOnWriteArrayList()
    val listPlans: MutableList<ListQueryPlanV1<*>> = CopyOnWriteArrayList()
    val pagePlans: MutableList<PageQueryPlanV1<*>> = CopyOnWriteArrayList()
    val countPlans: MutableList<CountQueryPlanV1> = CopyOnWriteArrayList()

    private var singlePublisher: Mono<Any> = Mono.empty()
    private var listPublisher: Flux<Any> = Flux.empty()
    private var pagePublisher: Mono<QueryPage<Any>> = Mono.just(QueryPage(emptyList(), 0, QueryConsistency.EXACT))
    private var countPublisher: Mono<Long> = Mono.just(0)

    fun respondSingle(publisher: Mono<*>): RecordingQueryBackend = apply {
        @Suppress("UNCHECKED_CAST")
        singlePublisher = publisher as Mono<Any>
    }

    fun respondList(publisher: Flux<*>): RecordingQueryBackend = apply {
        @Suppress("UNCHECKED_CAST")
        listPublisher = publisher as Flux<Any>
    }

    fun respondPage(publisher: Mono<out QueryPage<*>>): RecordingQueryBackend = apply {
        @Suppress("UNCHECKED_CAST")
        pagePublisher = publisher as Mono<QueryPage<Any>>
    }

    fun respondCount(publisher: Mono<Long>): RecordingQueryBackend = apply {
        countPublisher = publisher
    }

    override fun readiness(): Mono<QueryBackendReadiness> = Mono.defer {
        readinessSubscriptions.incrementAndGet()
        readinessPublisher
    }

    override fun <R : Any> single(plan: SingleQueryPlanV1<R>): Mono<R> {
        singlePlans += plan
        @Suppress("UNCHECKED_CAST")
        return observe(singlePublisher, singleSubscriptions) as Mono<R>
    }

    override fun <R : Any> list(plan: ListQueryPlanV1<R>): Flux<R> {
        listPlans += plan
        @Suppress("UNCHECKED_CAST")
        return observe(listPublisher, listSubscriptions) as Flux<R>
    }

    override fun <R : Any> page(plan: PageQueryPlanV1<R>): Mono<QueryPage<R>> {
        pagePlans += plan
        @Suppress("UNCHECKED_CAST")
        return observe(pagePublisher, pageSubscriptions) as Mono<QueryPage<R>>
    }

    override fun count(plan: CountQueryPlanV1): Mono<Long> {
        countPlans += plan
        return observe(countPublisher, countSubscriptions)
    }

    private fun <T : Any> observe(publisher: Mono<T>, subscriptions: AtomicInteger): Mono<T> = Mono.defer {
        subscriptions.incrementAndGet()
        publisher
    }.doOnCancel(cancellations::incrementAndGet).doFinally(::recordTerminal)

    private fun <T : Any> observe(publisher: Flux<T>, subscriptions: AtomicInteger): Flux<T> = Flux.defer {
        subscriptions.incrementAndGet()
        publisher
    }.doOnCancel(cancellations::incrementAndGet).doFinally(::recordTerminal)

    private fun recordTerminal(signal: SignalType) {
        if (signal != SignalType.CANCEL) {
            terminals.incrementAndGet()
        }
    }
}
