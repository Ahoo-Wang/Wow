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

package me.ahoo.wow.query.memory

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.DefaultQueryGateway
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.admission.QueryBudget
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.error.QueryTimeoutException
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.schema.FixedQueryModelSchemaProvider
import me.ahoo.wow.query.tck.QueryBackendSpec
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

class InMemoryQueryBackendTest : QueryBackendSpec() {
    private val storage = InMemoryQueryStorage()

    override suspend fun store(snapshots: List<SnapshotRecord>) {
        snapshots.forEach(storage::save)
    }

    override fun binding(type: AggregateType): QueryBackendBinding<QueryBackend> = storage.create(type)

    @Test
    fun `a snapshot replaces the stored one only with a higher version`() {
        val first = snapshot("o1", """{"name":"first"}""", version = 2)
        storage.save(first).assert().isTrue()
        storage.save(snapshot("o1", """{"name":"older"}""", version = 1)).assert().isFalse()
        storage.save(snapshot("o1", """{"name":"same"}""", version = 2)).assert().isFalse()
        storage.save(snapshot("o1", """{"name":"newer"}""", version = 3)).assert().isTrue()
        storage.records(type).single().path("state").path("name").stringValue().assert().isEqualTo("newer")
        storage.records(AggregateType("none", "none")).assert().isEmpty()
    }

    @Test
    fun `a rebuilt snapshot replaces the stored one of the same version, not a higher one`() {
        storage.rewrite(snapshot("o1", """{"name":"first"}""", version = 2)).assert().isTrue()
        storage.rewrite(snapshot("o1", """{"name":"rebuilt"}""", version = 2)).assert().isTrue()
        storage.rewrite(snapshot("o1", """{"name":"older"}""", version = 1)).assert().isFalse()
        storage.records(type).single().path("state").path("name").stringValue().assert().isEqualTo("rebuilt")
    }

    @Test
    fun `a projection reaches into the elements of an array, and leaves the stored record whole`() {
        runBlocking {
            val fixture = given(orders())
            val included = fixture.list(
                ListQuery(
                    IdFilter("o1"),
                    projection = Projection(include = listOf(QueryField("state.lines.sku"), QueryField("state.name"))),
                ),
            ).single()
            included.path("state").path("name").stringValue().assert().isEqualTo("Alpha")
            included.path("state").path("lines").toList().map { it.path("sku").stringValue() }.assert()
                .containsExactly("A", "B")
            included.path("state").path("lines").first().has("price").assert().isFalse()
            included.path("state").has("amount").assert().isFalse()
            val excluded = fixture.list(
                ListQuery(
                    IdFilter("o1"),
                    projection = Projection(
                        exclude = listOf(QueryField("state.lines.price"), QueryField("state.name"))
                    ),
                ),
            ).single()
            excluded.path("state").has("name").assert().isFalse()
            excluded.path("state").path("lines").first().has("price").assert().isFalse()
            excluded.path("state").path("lines").first().path("sku").stringValue().assert().isEqualTo("A")
            storage.records(type).first().path("state").path("lines").first().has("price").assert().isTrue()
        }
    }

    @Test
    fun `the gateway hands the budget's timeout to the backend`() {
        runBlocking {
            val fixture = given(orders())
            val timeout = Duration.ofSeconds(7)
            val policy =
                QueryEntryPolicy(http = QueryBudget(QueryBudget.HTTP_LABEL, timeout = timeout.toKotlinDuration()))
            val gateway = DefaultQueryGateway(
                type,
                fixture.backend,
                FixedQueryModelSchemaProvider(fixture.schema),
                entryPolicy = policy,
            )
            gateway.count(CountQuery(MatchAllFilter), QueryCaller(entry = QueryEntry.HTTP))
            gateway.count(CountQuery(MatchAllFilter), QueryCaller.IN_PROCESS)
            (fixture.backend as InMemoryQueryBackend).timeouts.assert()
                .containsExactly(timeout, QueryBudget.DEFAULT_TIMEOUT.toJavaDuration())
        }
    }

    @Test
    fun `a backend slower than the timeout fails with the timeout error`() {
        runBlocking {
            val slow = InMemoryQueryStorage(delay = Duration.ofSeconds(5))
            orders().forEach(slow::save)
            val fixture = given(orders())
            val binding = slow.create(type)
            val policy = QueryEntryPolicy(inProcessTimeout = Duration.ofMillis(50).toKotlinDuration())
            val gateway = DefaultQueryGateway(
                type,
                binding.backend,
                FixedQueryModelSchemaProvider(fixture.schema),
                entryPolicy = policy,
            )
            val count = runCatching { gateway.count(CountQuery(MatchAllFilter), QueryCaller.IN_PROCESS) }
            (count.exceptionOrNull() is QueryTimeoutException).assert().isTrue()
            val list = runCatching { gateway.list(ListQuery(MatchAllFilter), QueryCaller.IN_PROCESS).toList() }
            (list.exceptionOrNull() is QueryTimeoutException).assert().isTrue()
        }
    }
}
