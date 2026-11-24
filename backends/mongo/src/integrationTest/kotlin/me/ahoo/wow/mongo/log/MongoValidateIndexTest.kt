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

import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.log.AppendOutcome
import org.junit.jupiter.api.Test

/**
 * Deep review r1 (MongoDB backend): `ddl: validate` "checks that each structure exists in the format this version
 * reads" ([Ddl.VALIDATE]), but an index is checked by its name alone (`MongoFormat.index`): a version key created by
 * hand without `unique` (or on other fields) passes, and the log then commits two streams at one version of one
 * aggregate.
 */
class MongoValidateIndexTest : MongoLogFixtures() {
    @Test
    fun `a log that validates its schema refuses a version key that is not unique`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext("validate")
        val order = AggregateType(context, "order")
        // The schema as `ddl auto` creates it, then the version key replaced by hand with one of the same name.
        MongoLogFixture.log(1, context, aggregates = listOf(order.name))
        val log = MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)
            .getCollection(collection(context, order.name))
        log.dropIndex(VERSION_KEY)
        log.createIndex(
            Indexes.ascending("partition", "aggregate_id", "tenant_id", "version"),
            IndexOptions().name(VERSION_KEY),
        )

        val validated = runCatching {
            MongoEventLog(
                MongoLogFixture.client,
                MongoLogFixture.DATABASE,
                context,
                1,
                options = MongoEventLogOptions(ddl = Ddl.VALIDATE),
            ).also {
                it.createSchema()
                it.createCollection(order.name)
            }
        }

        // Either the start refuses the schema, or the version key still refuses a second stream at version 1.
        validated.getOrNull()?.let { opened ->
            opened.append(
                stream("o-1", 1, aggregate = order)
            ).assert().isInstanceOf(AppendOutcome.Committed::class.java)
            opened.append(stream("o-1", 1, aggregate = order)).assert().isInstanceOf(AppendOutcome.Conflict::class.java)
        }
    }

    private companion object {
        const val VERSION_KEY = "version_key"
    }
}
