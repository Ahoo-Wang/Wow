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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.access.AccessWalk
import me.ahoo.wow.spi.log.AppendOutcome
import org.junit.jupiter.api.Test

/** The access attributes of the log: the walk of a type, and its collection made on its first append. */
class MongoLogAccessTest : MongoLogFixtures() {
    @Test
    fun `the access walk of a type without history creates no collection, and its first append creates it`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val log = MongoLogFixture.log(2, context)
            val order = AggregateType(context, "order")
            val snapshot = AggregateType(context, "snapshot")
            log.append(stream("o-1", 1, aggregate = order))
            // What a walk's run reads and writes of a type, all of it, before the type's first stream.
            val walk = AccessWalk("", mapOf(0 to (log.head(0) ?: 0L), 1 to (log.head(1) ?: 0L)))
            log.holds(snapshot).assert().isFalse()
            log.walk(snapshot).assert().isNull()
            log.claim(snapshot, walk, expected = null).assert().isFalse()
            log.advance(snapshot, "", null, walked = 0, completed = true).assert().isFalse()
            log.aggregates(snapshot, null, limit = 10).refs.assert().isEmpty()
            val database = MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)
            val registered = {
                database.getCollection(MongoEventLog.REGISTRY).find(Filters.eq(MongoEventLog.CONTEXT, context))
                    .map { it.getString(MongoEventLog.AGGREGATE) }.toList()
            }
            val name = collection(context, "snapshot")
            database.listCollectionNames().toList().assert().doesNotContain(name)
            // The blocks list the registered collections, and a seal writes into each: the type's is not among them.
            registered().assert().containsExactly("order")

            log.append(stream("s-1", 1, aggregate = snapshot)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
            database.getCollection(name).listIndexes().mapNotNull { it.getString("name") }.toList()
                .assert().contains(MongoEventLog.VERSION_KEY, MongoEventLog.REQUEST_KEY)
            registered().assert().containsExactly("order", "snapshot")
            // Another node finds it through the registry, and claims its walk beside it.
            val other = MongoLogFixture.log(2, context)
            other.holds(snapshot).assert().isTrue()
            other.claim(snapshot, walk, expected = null).assert().isTrue()
            log.walk(snapshot).assert().isEqualTo(walk)
            log.aggregates(snapshot, null, limit = 10).refs.assert().containsExactly(snapshot.ref("tenant", "s-1"))
        }
}
