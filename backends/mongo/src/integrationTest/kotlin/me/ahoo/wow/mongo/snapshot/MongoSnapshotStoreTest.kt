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

import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.mongo.CountedMongo
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.tck.ExactJson
import me.ahoo.wow.tck.snapshot.SnapshotStoreSpec
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test

class MongoSnapshotStoreTest : SnapshotStoreSpec() {
    override fun createStore(context: String): SnapshotStore = store(context)

    private fun store(context: String = MongoLogFixture.newContext()) =
        MongoSnapshotStore(MongoLogFixture.client, MongoLogFixture.DATABASE, context)
            .also { it.createCollection("aggregate") }

    private fun collection(context: String, aggregate: String = "aggregate") =
        "wow_snapshot__${context.replace('-', '_')}__$aggregate"

    @Test
    fun `creating the schema twice keeps the snapshots`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val saved = snapshot("a", 1).copyWith(ref = AggregateType(context, "aggregate").ref("tenant", "a"))
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
        val saved = snapshot("a", 1).copyWith(ref = AggregateType(context, "aggregate").ref("tenant", "a"))
        val tooPrecise = saved.copyWith(state = """{"items":[{"price":1.2345678901234567890123456789012345}]}""")
        assertThrownBy<IllegalArgumentException> { runBlocking { store.save(tooPrecise) } }
        store.load(saved.ref).assert().isNull()
        // So is an exponent Decimal128 cannot hold, or holds only by changing the scale.
        listOf("1E-6200", "1E+6120").forEach { number ->
            assertThrownBy<IllegalArgumentException> { runBlocking { store.save(saved.copyWith(state = """{"n":$number}""")) } }
        }
        store.save(saved).assert().isTrue()
    }

    @Test
    fun `a state stored as BSON reads back as JSON, a double stored before decimals were exact too`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val store = store(context)
            val saved = snapshot("a", 1).copyWith(ref = AggregateType(context, "aggregate").ref("tenant", "a"))
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
        val saved = snapshot("a", 1).copyWith(ref = AggregateType(context, "aggregate").ref("tenant", "a"))
        assertThrownBy<IllegalArgumentException> { runBlocking { store(context).save(saved.copyWith(state = "[1]")) } }
    }

    @Test
    fun `a snapshot of another context is refused`(): Unit = runBlocking {
        val store = store()
        val foreign = snapshot("a", 1).copyWith(ref = AggregateType("elsewhere", "aggregate").ref("tenant", "a"))
        assertThrownBy<IllegalArgumentException> { runBlocking { store.save(foreign) } }
        assertThrownBy<IllegalArgumentException> { runBlocking { store.load(foreign.ref) } }
    }

    @Test
    fun `a save is one round trip unless the snapshot is marked stale`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val store = MongoSnapshotStore(CountedMongo.client, MongoLogFixture.DATABASE, context)
            .also { it.createCollection("aggregate") }
        val type = AggregateType(context, "aggregate")
        val first = snapshot("a", 1).copyWith(ref = type.ref("tenant", "a"))
        val name = collection(context)
        fun sent(block: suspend () -> Boolean): List<CountedMongo.Sent> =
            CountedMongo.commandsOf { runBlocking { block() }.assert().isTrue() }.second

        sent { store.save(first) }.assert().containsExactly(CountedMongo.Sent("findAndModify", name))
        sent { store.save(first.copyWith(version = 2)) }.assert()
            .containsExactly(CountedMongo.Sent("findAndModify", name))
        // A stale mark up to the saved version is cleared by a second write; one above it is kept without one.
        store.markStale(first.ref).assert().isTrue()
        sent { store.save(first.copyWith(version = 3)) }.assert()
            .containsExactly(CountedMongo.Sent("findAndModify", name), CountedMongo.Sent("update", name))
        store.pending(type, 1, limit = 10).assert().isEmpty()
        sent { store.rewrite(first.copyWith(version = 3)) }.assert()
            .containsExactly(CountedMongo.Sent("findAndModify", name))
    }

    @Test
    fun `a batch of one aggregate type is one bulk write, stale marks included`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val store = MongoSnapshotStore(CountedMongo.client, MongoLogFixture.DATABASE, context)
            .also { it.createCollection("aggregate") }
        val type = AggregateType(context, "aggregate")
        val name = collection(context)
        fun of(id: String, version: Int) = snapshot(id, version).copyWith(ref = type.ref("tenant", id))
        val batch = (1..BATCH).map { of("s$it", 1) }
        val (first, sent) = CountedMongo.commandsOf { runBlocking { store.saveAll(batch) } }
        first.forEach { it.assert().isEqualTo(SnapshotSaveOutcome.Written) }
        sent.assert().containsExactly(CountedMongo.Sent("update", name))
        // A stale mark up to the saved version is cleared in the same write; one above it stays.
        store.markStale(batch[0].ref).assert().isTrue()
        store.save(of("above", 1).copyWith(stateRevision = 2)).assert().isTrue()
        store.save(of("above", 5)).assert().isFalse()
        val (second, marked) = CountedMongo.commandsOf {
            runBlocking { store.saveAll(listOf(of("s1", 2), of("above", 3).copyWith(stateRevision = 2))) }
        }
        second.assert().containsExactly(SnapshotSaveOutcome.Written, SnapshotSaveOutcome.Written)
        marked.assert().containsExactly(CountedMongo.Sent("update", name))
        store.pending(type, 2, limit = 10).assert().contains(type.ref("tenant", "above"))
        store.pending(type, 1, limit = 10).assert().doesNotContain(batch[0].ref)
        // A refused one meets the key and is saved again alone.
        val (third, refused) = CountedMongo.commandsOf { runBlocking { store.saveAll(listOf(of("s2", 1), of("s3", 2))) } }
        third.assert().containsExactly(SnapshotSaveOutcome.Refused, SnapshotSaveOutcome.Written)
        refused.map { it.name }.assert().containsExactly("update", "findAndModify", "findAndModify", "update")
    }

    @Test
    fun `a batch's snapshot whose state cannot be BSON fails alone`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val store = store(context)
        val type = AggregateType(context, "aggregate")
        val saved = snapshot("a", 1).copyWith(ref = type.ref("tenant", "a"))
        val tooPrecise = snapshot("b", 1).copyWith(
            ref = type.ref("tenant", "b"),
            state = """{"price":1.2345678901234567890123456789012345}""",
        )
        val outcomes = store.saveAll(listOf(saved, tooPrecise))
        outcomes[0].assert().isEqualTo(SnapshotSaveOutcome.Written)
        (outcomes[1] as SnapshotSaveOutcome.Failed).cause.assert().isInstanceOf(IllegalArgumentException::class.java)
        store.saveAll(listOf(tooPrecise)).single().assert().isInstanceOf(SnapshotSaveOutcome.Failed::class.java)
        store.load(tooPrecise.ref).assert().isNull()
    }

    @Test
    fun `a batch's snapshot the server refuses fails alone`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val store = store(context)
        val type = AggregateType(context, "aggregate")
        // A validator the operator added refuses version 1000 and above, a refusal of the server per document.
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).runCommand(
            Document("collMod", collection(context)).append("validator", Document("version", Document("\$lt", 1000))),
        )
        val refused = snapshot("big", 1000).copyWith(ref = type.ref("tenant", "big"))
        val saved = snapshot("a", 1).copyWith(ref = type.ref("tenant", "a"))
        val outcomes = store.saveAll(listOf(saved, refused))
        outcomes[0].assert().isEqualTo(SnapshotSaveOutcome.Written)
        (outcomes[1] as SnapshotSaveOutcome.Failed).cause.assert().isInstanceOf(MongoWriteException::class.java)
        store.load(saved.ref)?.version.assert().isEqualTo(1)
        store.load(refused.ref).assert().isNull()
    }

    @Test
    fun `a batch writes field names with dots and dollars as a save does`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val store = store(context)
        val type = AggregateType(context, "aggregate")
        val state = """{"a.b":1,"nested":{"x.y":"${'$'}z","${'$'}literal":2},"text":"${'$'}notAField"}"""
        val batched = snapshot("a", 1).copyWith(ref = type.ref("tenant", "a"), state = state)
        store.saveAll(listOf(batched)).single().assert().isEqualTo(SnapshotSaveOutcome.Written)
        ExactJson.of(checkNotNull(store.load(batched.ref)).state).assert().isEqualTo(ExactJson.of(state))
    }

    @Test
    fun `an aggregate type's collection is created the first time it is touched`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val store = MongoSnapshotStore(MongoLogFixture.client, MongoLogFixture.DATABASE, context)
        store.createSchema()
        val saved = snapshot("a", 1).copyWith(ref = AggregateType(context, "Order-Line").ref("tenant", "a"))
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

    private companion object {
        const val BATCH = 20
    }
}
