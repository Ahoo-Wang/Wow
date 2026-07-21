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

package me.ahoo.wow.elasticsearch.query.event

import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.elasticsearch.TemplateInitializer.initEventStreamTemplate
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchEventStore
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.event.count
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import me.ahoo.wow.tck.query.EventStreamQueryServiceSpec
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.kotlin.test.test

class ElasticsearchEventStreamQueryServiceTest : EventStreamQueryServiceSpec() {
    @JvmField
    @RegisterExtension
    val elasticsearch = ElasticsearchTestFixture()

    lateinit var elasticsearchClient: ReactiveElasticsearchClient

    @BeforeEach
    override fun setup() {
        elasticsearchClient = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        elasticsearchClient.initEventStreamTemplate()
        super.setup()
    }

    override fun createEventStore(): EventStore {
        return ElasticsearchEventStore(elasticsearchClient)
    }

    override fun createEventStreamQueryServiceFactory(): EventStreamQueryServiceFactory {
        return ElasticsearchEventStreamQueryServiceFactory(elasticsearchClient)
    }

    @Test
    fun `should query event stream by stream id`() {
        val eventStream = generateEventStream(namedAggregate.aggregateId(generateGlobalId()))
        eventStore.append(eventStream).block()

        condition { id(eventStream.id) }
            .count(eventStreamQueryService)
            .test()
            .expectNext(1L)
            .verifyComplete()
    }

    @Test
    fun `should query null as a missing field`() {
        val eventStream = generateEventStream(
            namedAggregate.aggregateId(id = generateGlobalId(), tenantId = generateGlobalId())
        )
        eventStore.append(eventStream).block()

        condition {
            tenantId(eventStream.aggregateId.tenantId)
            "missingField".isNull()
        }
            .count(eventStreamQueryService)
            .test()
            .expectNext(1L)
            .verifyComplete()
        condition {
            tenantId(eventStream.aggregateId.tenantId)
            "missingField".notNull()
        }
            .count(eventStreamQueryService)
            .test()
            .expectNext(0L)
            .verifyComplete()
    }
}
