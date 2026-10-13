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

package me.ahoo.wow.mongo.snapshot

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.snapshot.SnapshotStoreSpec
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test

class MongoSnapshotStoreTest : SnapshotStoreSpec() {
    override fun createStore(context: String): SnapshotStore = store(context)

    private fun store(context: String = MongoLogFixture.newContext()) =
        MongoSnapshotStore(MongoLogFixture.client, MongoLogFixture.DATABASE, context, listOf("aggregate"))
            .also { it.createSchema() }

    private fun collection(context: String, aggregate: String = "aggregate") =
        "wow_snapshot__${context.replace('-', '_')}__$aggregate"

    @Test
    fun `creating the schema twice keeps the snapshots`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val saved = snapshot("a", 1).copy(ref = AggregateType(context, "aggregate").ref("tenant", "a"))
        store(context).save(saved).assert().isTrue()
        store(context).load(saved.ref)?.version.assert().isEqualTo(1)
    }

    @Test
    fun `snapshots recorded in another format version are refused`() {
        val context = MongoLogFixture.newContext()
        store(context)
        MongoLogFixture.bumpFormat(collection(context))
        assertThrownBy<IllegalStateException> { store(context) }
    }

    @Test
    fun `snapshots created by a build without format records are refused`() {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.createUnrecorded(collection(context))
        assertThrownBy<IllegalStateException> { store(context) }
    }

    @Test
    fun `a state decimal beyond 34 digits is refused at save, never rounded`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val store = store(context)
        val saved = snapshot("a", 1).copy(ref = AggregateType(context, "aggregate").ref("tenant", "a"))
        val tooPrecise = saved.copy(state = """{"items":[{"price":1.2345678901234567890123456789012345}]}""")
        assertThrownBy<IllegalArgumentException> { runBlocking { store.save(tooPrecise) } }
        store.load(saved.ref).assert().isNull()
        // So is an exponent Decimal128 cannot hold, or holds only by changing the scale.
        listOf("1E-6200", "1E+6120").forEach { number ->
            assertThrownBy<IllegalArgumentException> { runBlocking { store.save(saved.copy(state = """{"n":$number}""")) } }
        }
        store.save(saved).assert().isTrue()
    }

    @Test
    fun `a state stored as BSON reads back as JSON, a double stored before decimals were exact too`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val store = store(context)
            val saved = snapshot("a", 1).copy(ref = AggregateType(context, "aggregate").ref("tenant", "a"))
            store.save(saved).assert().isTrue()
            val snapshots = MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)
                .getCollection(collection(context))
            snapshots.updateOne(Filters.eq("aggregate_id", "a"), Updates.set("state", Document("price", 2.5)))
            store.load(saved.ref)!!.state.assert().isEqualTo("""{"price":2.5}""")
            // A BSON value JSON has no counterpart for is not a state Wow wrote.
            snapshots.updateOne(Filters.eq("aggregate_id", "a"), Updates.set("state", Document("id", ObjectId())))
            assertThrownBy<IllegalStateException> { runBlocking { store.load(saved.ref) } }
        }

    @Test
    fun `a state that is not a JSON object is refused`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val saved = snapshot("a", 1).copy(ref = AggregateType(context, "aggregate").ref("tenant", "a"))
        assertThrownBy<IllegalArgumentException> { runBlocking { store(context).save(saved.copy(state = "[1]")) } }
    }

    @Test
    fun `a snapshot of another context is refused`(): Unit = runBlocking {
        val store = store()
        val foreign = snapshot("a", 1).copy(ref = AggregateType("elsewhere", "aggregate").ref("tenant", "a"))
        assertThrownBy<IllegalArgumentException> { runBlocking { store.save(foreign) } }
        assertThrownBy<IllegalArgumentException> { runBlocking { store.load(foreign.ref) } }
    }

    @Test
    fun `an aggregate type's collection is created the first time it is touched`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val store = MongoSnapshotStore(MongoLogFixture.client, MongoLogFixture.DATABASE, context)
        store.createSchema()
        val saved = snapshot("a", 1).copy(ref = AggregateType(context, "Order-Line").ref("tenant", "a"))
        store.save(saved).assert().isTrue()
        store.save(saved).assert().isFalse()
        val database = MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)
        database.listCollectionNames().toList().assert().contains(collection(context, "order_line"))
        database.getCollection(collection(context, "order_line")).find().first()!!.containsKey("aggregate_name")
            .assert().isFalse()
        MongoLogFixture.bumpFormat(collection(context, "order_line"))
        val reopened = MongoSnapshotStore(MongoLogFixture.client, MongoLogFixture.DATABASE, context)
        assertThrownBy<IllegalStateException> { runBlocking { reopened.load(saved.ref) } }
    }
}
