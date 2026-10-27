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

package me.ahoo.wow.query.events

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.memory.InMemoryEventQueryStorage
import me.ahoo.wow.query.policy.AbacQueryOptions
import me.ahoo.wow.query.policy.AbacQueryPolicy
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test

/**
 * ABAC on the events model (decision D259): each stream is visible as its tags stood at its commit, and a stream that
 * recorded no tags matches no restriction, on the in-memory reference backend.
 */
class EventStreamAbacTest {
    private val type = AggregateType("shop", "order")

    private fun stream(id: String, version: Int, tags: Map<String, List<String>>?) = EventStreamRecord(
        ref = type.ref(TENANT, id),
        version = version,
        commandId = "cmd-$id-$version",
        requestId = "req-$id-$version",
        commandType = "shop.order.Change",
        commitTime = 1000L * version,
        events = listOf(EventRecord("shop.order.Changed", 1, "{}")),
        scope = Scope.NONE,
        tags = tags,
    )

    private val streams = listOf(
        // Moved from east to west, then untagged: each stream keeps the tags of its commit.
        stream("o-1", 1, mapOf("region" to listOf("east"))),
        stream("o-1", 2, mapOf("region" to listOf("west"))),
        stream("o-1", 3, emptyMap()),
        // Appended before the log learned the tags.
        stream("o-2", 1, null),
        // Another key only.
        stream("o-3", 1, mapOf("dept" to listOf("eng"))),
    )
    private val binding = InMemoryEventQueryStorage({ streams }).create(type)
    private val schema = QueryModelCompiler.of().compile(QuerySchemaContext(type, QueryModel.EVENTS), binding.storage)

    private suspend fun visible(options: AbacQueryOptions, vararg tags: Pair<String, List<String>>): List<String> {
        val gateway = EventQueryGateways.create(
            type,
            binding.backend,
            schema,
            policies = listOf(AbacQueryPolicy(options)),
            entryPolicy = QueryEntryPolicy(inProcessTimeout = null),
        )
        val caller = QueryCaller(
            Caller(tenantId = TENANT, attributes = tags.associate { (key, values) -> "abac.$key" to values }),
            entry = QueryEntry.HTTP,
        )
        val sort =
            listOf(Sort(QueryField("aggregateId"), Sort.Direction.ASC), Sort(QueryField("version"), Sort.Direction.ASC))
        return gateway.list(ListQuery(MatchAllFilter, sort = sort, limit = 100), caller).toList()
            .map { "${it["aggregateId"].asString()}@${it["version"].asInt()}" }
    }

    @Test
    fun `a stream is visible as its tags stood at its commit, one without tags never under a restriction`() =
        runTest {
            val on = AbacQueryOptions(enabled = true)
            visible(on, "region" to listOf("east")).assert().containsExactly("o-1@1", "o-1@3", "o-3@1")
            visible(on.copyWith(matchMissingTagKey = false), "region" to listOf("east")).assert()
                .containsExactly("o-1@1")
            visible(on, "region" to listOf("*")).assert().containsExactly("o-1@1", "o-1@2")
            visible(on, "region" to listOf("west"), "dept" to listOf("ops")).assert().containsExactly("o-1@2", "o-1@3")
            // No restriction: every stream, the untagged one too.
            visible(on).assert().containsExactly("o-1@1", "o-1@2", "o-1@3", "o-2@1", "o-3@1")
            visible(AbacQueryOptions(), "region" to listOf("east")).assert().hasSize(5)
        }

    @Test
    fun `a record shows the tags its stream recorded, null when none were`() = runTest {
        val gateway = EventQueryGateways.create(
            type,
            binding.backend,
            schema,
            entryPolicy = QueryEntryPolicy(inProcessTimeout = null),
        )
        val records = gateway.list(ListQuery(MatchAllFilter, limit = 100), QueryCaller.IN_PROCESS).toList()
            .associateBy { "${it["aggregateId"].asString()}@${it["version"].asInt()}" }
        records.getValue("o-1@1")["tags"]["region"][0].asString().assert().isEqualTo("east")
        records.getValue("o-1@3")["tags"].isEmpty.assert().isTrue()
        records.getValue("o-2@1")["tags"].isNull.assert().isTrue()
    }

    private companion object {
        const val TENANT = "acme"
    }
}
