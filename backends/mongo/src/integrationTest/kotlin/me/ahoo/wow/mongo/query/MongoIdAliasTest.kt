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

package me.ahoo.wow.mongo.query

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.error.QueryError
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * MongoDB's `$group` keeps a group's key in `_id`, and a metric is written under its alias beside it, so a metric
 * aliased `_id`, which the protocol admits, named the key a second time and did not answer what the reference does
 * (#7213). It is refused as the storage's limit, a query the caller can mend under another alias; a group aliased
 * `_id` is a field of the key and is answered. Over the snapshot collection a service queries, with its storage.
 */
class MongoIdAliasTest {
    private val type = AggregateType("ids_" + UUID.randomUUID().toString().replace("-", "").take(12), "order")
    private val status = QueryField("state.status")

    private fun aggregate(query: AggregationQuery): List<ObjectNode> = runBlocking {
        val store = MongoSnapshotStore(MongoLogFixture.client, MongoLogFixture.DATABASE, type.context)
        QueryFixtures.orders(type).forEach { store.save(it) }
        val binding = checkNotNull(
            MongoSnapshotQueryStorage(MongoLogFixture.client, MongoLogFixture.DATABASE, type.context).binding(type),
        )
        val schema = QueryFixtures.schema(type, binding.storage)
        binding.backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList()
    }

    @Test
    fun `a metric aliased _id should be refused as the storage's limit, with or without groups`() {
        listOf(
            AggregationQuery(metrics = listOf(AggregationMetric.Count("_id", MatchAllFilter))),
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(status, "status")),
                metrics = listOf(AggregationMetric.Count("_id", MatchAllFilter)),
            ),
        ).forEach { query ->
            val refused = runCatching { aggregate(query) }.exceptionOrNull() as QueryError
            refused.violations.single().code.assert().isEqualTo(QueryErrorCodes.STORAGE_UNSUPPORTED)
        }
    }

    @Test
    fun `a group aliased _id should answer the rows of the same group under another alias, sorted by it`() {
        fun grouped(alias: String, direction: Sort.Direction): List<Pair<String?, Long>> = aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(status, alias, missingKey = "none")),
                metrics = listOf(AggregationMetric.Count("orders", MatchAllFilter)),
                sort = listOf(Sort(QueryField(alias), direction)),
            ),
        ).map { row ->
            // A key is a scalar, never a document holding it.
            row[alias].isValueNode.assert().describedAs("$row").isTrue()
            row[alias].asString() to row["orders"].asLong()
        }
        Sort.Direction.entries.forEach { direction ->
            val reference = grouped("status", direction)
            reference.size.assert().isGreaterThan(1)
            grouped("_id", direction).assert().describedAs("$direction").isEqualTo(reference)
        }
    }
}
