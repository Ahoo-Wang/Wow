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

package me.ahoo.wow.query.schema

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.util.concurrent.atomic.AtomicInteger

class QuerySchemaCatalogTest {
    private val order = MaterializedNamedAggregate("example", "order")
    private val cart = MaterializedNamedAggregate("example", "cart")

    @Test
    fun `revalidation reloads every schema and a failed reload keeps the previous version`() {
        val first = boundSchemaFixture(objectFixture("name" to scalarFixture()))
        val second = boundSchemaFixture(objectFixture("name" to scalarFixture(), "note" to scalarFixture()))
        val provider = SequenceProvider(first, second, null)
        val catalog = QuerySchemaCatalog(listOf(QuerySchemaCatalog.Entry(order, QueryModel.SNAPSHOT, provider)))

        val initial = catalog.versions().blockFirst()!!
        initial.version.assert().isNotNull()
        initial.aggregate.assert().isEqualTo("example.order")

        val changed = catalog.revalidate().blockFirst()!!
        changed.version.assert().isNotEqualTo(initial.version)

        catalog.revalidate().test().assertNext {
            it.version.assert().isNull()
            it.error.assert().isEqualTo("storage unreachable")
        }.verifyComplete()
        catalog.versions().blockFirst()!!.version.assert().isEqualTo(changed.version)
    }

    @Test
    fun `revalidation publishes refresh timing, failures and version changes`() {
        val registry = io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        val first = boundSchemaFixture(objectFixture("name" to scalarFixture()))
        val second = boundSchemaFixture(objectFixture("name" to scalarFixture(), "note" to scalarFixture()))
        val catalog = QuerySchemaCatalog(
            listOf(QuerySchemaCatalog.Entry(order, QueryModel.SNAPSHOT, SequenceProvider(first, second, second, null))),
            registry,
        )
        catalog.versions().blockLast()
        repeat(3) { catalog.revalidate().blockLast() }

        fun timer(outcome: String) = registry.find(QuerySchemaCatalog.SCHEMA_REFRESH)
            .tags("context", "example", "aggregate", "order", "model", "snapshot", "outcome", outcome).timer()
        timer("success")!!.count().assert().isEqualTo(2)
        timer("failure")!!.count().assert().isEqualTo(1)
        // Only first → second changed the version; second → second and the failed reload did not.
        registry.find(QuerySchemaCatalog.SCHEMA_VERSION_CHANGES).counter()!!.count().assert().isEqualTo(1.0)
    }

    @Test
    fun `an aggregate can be selected and aggregates without a backend are skipped`() {
        val schema = boundSchemaFixture(objectFixture("name" to scalarFixture()))
        val catalog = QuerySchemaCatalog(
            listOf(
                QuerySchemaCatalog.Entry(order, QueryModel.SNAPSHOT, SequenceProvider(schema)),
                QuerySchemaCatalog.Entry(cart, QueryModel.SNAPSHOT, SequenceProvider(schema)),
                QuerySchemaCatalog.Entry(cart, QueryModel.EVENT_STREAM, UnavailableQueryModelSchemaProvider("none")),
            ),
        )
        catalog.versions().map { it.aggregate to it.model }.collectList().block()!!.assert()
            .containsExactly("example.order" to QueryModel.SNAPSHOT, "example.cart" to QueryModel.SNAPSHOT)
        catalog.revalidate("example.cart").map { it.aggregate }.collectList().block()!!.assert()
            .containsExactly("example.cart")
    }

    /** Serves [loads] in turn: the first on [schema], later ones on [refresh]; `null` fails the reload. */
    private class SequenceProvider(private vararg val loads: QueryModelSchema?) : QueryModelSchemaProvider {
        private val index = AtomicInteger()

        @Volatile
        private var published: QueryModelSchema? = null

        override fun schema(): Mono<QueryModelSchema> = published?.let { Mono.just(it) } ?: refresh()

        override fun refresh(): Mono<QueryModelSchema> {
            val next = loads.getOrNull(index.getAndIncrement().coerceAtMost(loads.size - 1))
                ?: return Mono.error(IllegalStateException("storage unreachable"))
            published = next
            return Mono.just(next)
        }
    }
}
