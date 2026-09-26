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

package me.ahoo.wow.spring.boot.starter.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.event.EventStreamQueryBackend
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.tck.query.NoOpEventStreamQueryBackend
import me.ahoo.wow.tck.query.NoOpSnapshotQueryBackend
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger

class QuerySchemaCatalogAutoConfigurationTest {
    private val refreshes = AtomicInteger()

    private fun provider(model: QueryModel) = object : QueryModelSchemaProvider {
        private val schema = testQuerySchema(model)
        override fun schema(): Mono<QueryModelSchema> = Mono.just(schema)
        override fun refresh(): Mono<QueryModelSchema> = Mono.fromSupplier {
            refreshes.incrementAndGet()
            schema
        }
    }

    private val snapshotFactory = object : SnapshotQueryBackendFactory {
        override fun create(namedAggregate: NamedAggregate): QueryBackendBinding<SnapshotQueryBackend> =
            QueryBackendBinding(NoOpSnapshotQueryBackend(namedAggregate), FIXED_STORAGE)
    }
    private val eventFactory = EventStreamQueryBackendFactory { namedAggregate ->
        QueryBackendBinding<EventStreamQueryBackend>(
            NoOpEventStreamQueryBackend(namedAggregate),
            FIXED_STORAGE,
        )
    }

    private fun runner() = ApplicationContextRunner().enableWow()
        .withUserConfiguration(QueryAutoConfiguration::class.java, QuerySchemaCatalogAutoConfiguration::class.java)
        .withBean(SnapshotQueryBackendFactory::class.java, { snapshotFactory })
        .withBean(EventStreamQueryBackendFactory::class.java, { eventFactory })
        .withBean(QueryModelCompiler::class.java, {
            // One provider per compiled model, as the Catalog compiles each model once.
            QueryModelCompiler { context, _ -> provider(context.model) }
        })

    @Test
    fun `the catalog covers every aggregate and the endpoint reports and revalidates per instance`() {
        runner().withPropertyValues("wow.query.schema.revalidate-interval=0s").run { context ->
            context.assert().hasNotFailed()
                .hasSingleBean(QuerySchemaCatalog::class.java)
                .hasSingleBean(QuerySchemaEndpoint::class.java)
            val endpoint = context.getBean(QuerySchemaEndpoint::class.java)
            val versions = endpoint.versions().block()!!
            versions.assert().isNotEmpty()
            versions.map { it.model }.toSet().assert().containsExactlyInAnyOrder(
                QueryModel.SNAPSHOT,
                QueryModel.EVENT_STREAM
            )
            versions.all { it.version!!.startsWith("sha256:") }.assert().isTrue()

            val aggregate = versions.first().aggregate
            endpoint.revalidate(aggregate).block()!!.map { it.aggregate }.toSet().assert().containsExactly(aggregate)
            refreshes.get().assert().isEqualTo(2)
            context.getBean(QuerySchemaRevalidation::class.java).isRunning.assert().isFalse()
        }
    }

    @Test
    fun `aggregates without a query backend are left out of the catalog`() {
        ApplicationContextRunner().enableWow()
            .withUserConfiguration(QueryAutoConfiguration::class.java, QuerySchemaCatalogAutoConfiguration::class.java)
            .withPropertyValues("wow.query.schema.revalidate-interval=0s")
            .run { context ->
                context.getBean(QuerySchemaCatalog::class.java).versions().collectList().block()!!.assert().isEmpty()
            }
    }

    @Test
    fun `periodic revalidation runs while the application runs`() {
        runner().withPropertyValues("wow.query.schema.revalidate-interval=1h").run { context ->
            context.getBean(QuerySchemaRevalidation::class.java).isRunning.assert().isTrue()
        }
    }
}
