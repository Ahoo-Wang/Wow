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

package me.ahoo.wow.spring.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.PageQueryRequest
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryPage
import me.ahoo.wow.api.query.gateway.SingleQueryRequest
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.event.GatewayEventStreamQueryService
import me.ahoo.wow.query.snapshot.GatewaySnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.core.ResolvableType
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.AbstractMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class QueryServiceRegistrarGatewayTest {
    @Test
    fun `registrars preserve bean identity and use the application query gateway directly`() {
        val beanFactory = DefaultListableBeanFactory()
        val gateway = RecordingQueryGateway()
        val legacyFactoryCalls = AtomicInteger()
        beanFactory.registerSingleton("queryGateway", gateway)
        beanFactory.registerSingleton(
            "legacySnapshotFactory",
            object : SnapshotQueryServiceFactory {
                override fun <S : Any> create(namedAggregate: NamedAggregate): SnapshotQueryService<S> {
                    legacyFactoryCalls.incrementAndGet()
                    error("legacy snapshot factory must not be selected")
                }
            }
        )
        beanFactory.registerSingleton(
            "legacyEventFactory",
            EventStreamQueryServiceFactory {
                legacyFactoryCalls.incrementAndGet()
                error("legacy event factory must not be selected")
            }
        )
        val metadata = aggregateMetadata<RegistrarAggregate, RegistrarState>()
        val namedAggregate = metadata.materialize()
        val entry = AbstractMap.SimpleImmutableEntry(namedAggregate, RegistrarAggregate::class.java)

        SnapshotQueryServiceRegistrar().apply { setBeanFactory(beanFactory) }
            .registerQueryService(entry, beanFactory)
        EventStreamQueryServiceRegistrar().apply { setBeanFactory(beanFactory) }
            .registerQueryService(entry, beanFactory)

        val snapshotName = "${namedAggregate.toStringWithAlias()}.SnapshotQueryService"
        val eventName = "${namedAggregate.toStringWithAlias()}.EventStreamQueryService"
        val snapshot = beanFactory.getBean(snapshotName)
        val event = beanFactory.getBean(eventName)
        snapshot.assert().isInstanceOf(GatewaySnapshotQueryService::class.java)
        event.assert().isInstanceOf(GatewayEventStreamQueryService::class.java)
        beanFactory.getBeanNamesForType(
            ResolvableType.forClassWithGenerics(SnapshotQueryService::class.java, RegistrarState::class.java)
        ).toList().assert().contains(snapshotName)
        beanFactory.getBeanNamesForType(EventStreamQueryService::class.java).toList().assert().contains(eventName)

        @Suppress("UNCHECKED_CAST")
        (snapshot as SnapshotQueryService<RegistrarState>)
            .count(me.ahoo.wow.api.query.Condition.ALL).toFuture().join().assert().isZero()
        (event as EventStreamQueryService)
            .count(me.ahoo.wow.api.query.Condition.ALL).toFuture().join().assert().isZero()

        legacyFactoryCalls.get().assert().isZero()
        gateway.targets.map { it.documentKind }.assert().containsExactly(
            QueryDocumentKind.SNAPSHOT,
            QueryDocumentKind.EVENT_STREAM
        )
    }
}

@AggregateRoot
private class RegistrarAggregate(val state: RegistrarState)

private data class RegistrarState(val id: String)

private class RecordingQueryGateway : QueryGateway {
    val targets = CopyOnWriteArrayList<me.ahoo.wow.api.query.gateway.QueryTarget>()

    override fun <R : Any> single(request: SingleQueryRequest<R>): Mono<R> = Mono.empty()

    override fun <R : Any> list(request: ListQueryRequest<R>): Flux<R> = Flux.empty()

    override fun <R : Any> page(request: PageQueryRequest<R>): Mono<QueryPage<R>> = Mono.empty()

    override fun count(request: CountQueryRequest): Mono<Long> = Mono.fromSupplier {
        targets += request.target
        0
    }
}
