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
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.error.QueryError
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A text with an unpaired surrogate (`"\ud800"` in a JSON body) reached MongoDB as a `$regex` it refuses (error 51091,
 * "UTF-8 error: code points 0xd800-0xdfff are not defined"), answered as a storage failure (#7211). Admission refuses
 * it, as a value mismatch of its field, before the store is asked; a surrogate pair is a text as any other.
 */
class MongoUnpairedSurrogateTest {
    private val type = AggregateType("pairs_" + UUID.randomUUID().toString().replace("-", "").take(12), "order")
    private val name = QueryField("state.name")

    private fun ids(filter: FilterExpression): List<String> = runBlocking {
        val orders = QueryFixtures.orders(type)
        val store = MongoSnapshotStore(MongoLogFixture.client, MongoLogFixture.DATABASE, type.context)
        orders.forEach { store.save(it) }
        val binding = checkNotNull(
            MongoSnapshotQueryStorage(MongoLogFixture.client, MongoLogFixture.DATABASE, type.context).binding(type),
        )
        val schema = QueryFixtures.schema(type, binding.storage)
        binding.backend.list(QueryAdmission.Trusted.list(ListQuery(filter, limit = 10), schema)).toList()
            .map { it.path("aggregateId").stringValue() }
    }

    @Test
    fun `a text with an unpaired surrogate should be refused before the store, not answered as its failure`() {
        listOf("a\uD800", "\uD800b", "\uDC00").forEach { text ->
            StringComparison.entries.forEach { comparison ->
                val filter = ContainsFilter(name, text, comparison)
                val refused = runCatching { ids(filter) }.exceptionOrNull() as QueryError
                refused.violations.single().code.assert().isEqualTo(QueryErrorCodes.VALUE_MISMATCH)
            }
        }
        (runCatching { ids(EqualFilter(name, "a\uD800")) }.exceptionOrNull() as QueryError).violations.single().code
            .assert().isEqualTo(QueryErrorCodes.VALUE_MISMATCH)
        ids(ContainsFilter(name, "😀", StringComparison.CASE_INSENSITIVE)).assert().isEmpty()
    }
}
