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

package me.ahoo.wow.mongo.log

import com.mongodb.client.model.Filters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.log.AppendOutcome
import org.junit.jupiter.api.Test

/**
 * Deep review r1 (MongoDB backend), from PostgreSQL's #6751: under `ddl: validate` or `none` the log writes no
 * registry record for an aggregate collection (`MongoLogCollections.ensure`: "The registry is the schema's"), and a
 * tail or `head` reads only the collections the registry lists, beside those its own node appended to since it
 * started. A collection that exists without its record (a database restored without the registry's documents,
 * collections made by hand) is then read by no other node's tail, nor by its own after a restart.
 */
class MongoReviewR1RegistryTest : MongoLogFixtures() {
    @Test
    fun `entries of a log collection without its registry record are tailed by another node`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext("registry")
        val order = AggregateType(context, "order")
        // The schema as `ddl: auto` makes it, then restored without the registry's record of `order`.
        MongoLogFixture.log(1, context, listOf(order.name))
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).getCollection(MongoEventLog.REGISTRY)
            .deleteOne(Filters.eq("_id", MongoNames.of("wow_log", context, order.name)))
        val writer = validating(context)
        writer.append(stream("a", 1, aggregate = order)).assert().isInstanceOf(AppendOutcome.Committed::class.java)

        // Another node, or the writer after a restart.
        val reader = validating(context)

        withTimeout(TAIL_WAIT) { reader.tail(0).first() }.stream.ref.id.assert().isEqualTo("a")
        reader.head(0).assert().isEqualTo(1L)
    }

    private fun validating(context: String) = MongoEventLog(
        MongoLogFixture.client,
        MongoLogFixture.DATABASE,
        context,
        1,
        options = MongoEventLogOptions(ddl = Ddl.VALIDATE),
    ).also { it.createSchema() }

    private companion object {
        const val TAIL_WAIT = 10_000L
    }
}
