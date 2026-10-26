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

import com.mongodb.ReadPreference
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.list
import me.ahoo.wow.query.tck.EventQueryBackendSpec
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventStreamRecord
import org.bson.Document
import org.junit.jupiter.api.Test

/** The events backend contract against MongoDB (decision D235): each test's fresh context is a fresh log. */
class MongoEventQueryBackendTest : EventQueryBackendSpec() {
    override suspend fun store(streams: List<Pair<EventStreamRecord, ByteArray?>>) {
        val log = MongoLogFixture.log(PARTITIONS, type.context, listOf(type.name))
        streams.forEach { (stream, transport) ->
            (log.append(stream, transport) is AppendOutcome.Committed).assert().isTrue()
        }
    }

    override fun binding(type: AggregateType): QueryBackendBinding<QueryBackend> =
        MongoEventQueryStorage(MongoLogFixture.client, MongoLogFixture.DATABASE, type.context).create(type)

    @Test
    fun `the reads may go to a secondary`(): Unit = runBlocking {
        val schema = given().schema
        val secondary = MongoEventQueryStorage(
            MongoLogFixture.client,
            MongoLogFixture.DATABASE,
            type.context,
            readPreference = ReadPreference.secondaryPreferred(),
        ).create(type).backend
        // The test replica set has one member: the read falls back to the primary.
        secondary.list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema)).toList().assert().hasSize(7)
    }

    @Test
    fun `a query reads the streams of the log collection, never its placeholders`(): Unit = runBlocking {
        val schema = given().schema
        // Decision D271: a sequence the log allocated and did not write holds a placeholder, which has no version.
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)
            .getCollection(MongoEventQueryStorage(MongoLogFixture.client, MongoLogFixture.DATABASE, type.context)
                .collection(type.name))
            .insertOne(Document("partition", 0).append("sequence", Long.MAX_VALUE).append("gap", "failed"))
        val backend = binding(type).backend
        backend.list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema)).toList().assert().hasSize(7)
        backend.count(QueryAdmission.Trusted.count(CountQuery(MatchAllFilter), schema)).assert().isEqualTo(7L)
    }

    private companion object {
        const val PARTITIONS = 4
    }
}
