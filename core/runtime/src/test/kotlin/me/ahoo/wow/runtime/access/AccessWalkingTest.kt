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

package me.ahoo.wow.runtime.access

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.CachedState
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.spi.access.AccessStamp
import me.ahoo.wow.spi.access.AccessStamps
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.access.AccessWalk
import me.ahoo.wow.spi.access.AccessWalks
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.ObservedEvent.AccessStamped.Cause
import me.ahoo.wow.spi.observation.Observer
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The access walks: each type's history stamped with its aggregates' current attributes, where no commit did. */
class AccessWalkingTest {
    class Document(val id: String) {
        var department: String? = null
    }

    data class Move(val department: String)

    data class Moved(val department: String)

    private val department = AccessAttribute.single<Document, String>("department", String::class.java)
    private val model = aggregate<Document>("office", "document") {
        initial { Document(it.id) }
        decide<Move>(Creation.ALLOWED) { _, command -> Decision.events(Moved(command.department)) }
        evolve<Moved> { department = it.department }
        access(department) { it.department }
    }
    private val type = model.type
    private val options = AccessStampOptions(walkBatch = 2, walkRate = 1_000_000, interval = 1.seconds)
    private val observed = ArrayList<ObservedEvent>()
    private val observer = object : Observer {
        override fun observed(event: ObservedEvent) {
            observed += event
        }
    }

    private fun access(department: String) = AccessValues.ofStream(mapOf("department" to department), Scope("alice"))

    /** Appends to [log] the streams of [ids], each moved to [departments] in turn, without attributes. */
    private fun unstamped(log: InMemoryEventLog, ids: List<String>, vararg departments: String) = runBlocking {
        val written = InMemoryEventLog()
        val kernel = Kernel(written)
        ids.forEach { id ->
            val ref = type.ref("tenant", id)
            departments.forEachIndexed { index, moved ->
                kernel.execute(model, Command("c-$id-$index", "r-$id-$index", ref, Move(moved), scope = Scope("alice")))
            }
            written.read(ref).toList().forEach { log.append(it.copyWith(access = null)) }
        }
    }

    private fun history(log: InMemoryEventLog, id: String) =
        runBlocking { log.read(type.ref("tenant", id)).toList().map { it.access } }

    private fun heads(log: InMemoryEventLog) = runBlocking { (0 until log.partitions).associateWith { log.head(it) ?: 0L } }

    private fun walking(log: InMemoryEventLog, leases: InMemoryLeaseStore = InMemoryLeaseStore()) = AccessWalking(
        "office",
        "service/office",
        "n-1",
        listOf(model),
        Kernel(log),
        { log },
        leases,
        options,
        observer = observer,
    )

    @Test
    fun `a walk stamps each aggregate's whole history with its current attributes, a page at a time, and completes`(): Unit =
        runBlocking {
            val log = InMemoryEventLog()
            val ids = listOf("d-1", "d-2", "d-3")
            unstamped(log, ids, "sales", "legal")
            val walking = walking(log)
            walking.maintain().assert().isFalse()
            ids.forEach { history(log, it).assert().containsExactly(access("legal"), access("legal")) }
            val walk = AccessWalk(AccessSchema.of(model), heads(log), log.aggregates(type, null, 2).next, 3, true)
            log.walk(type).assert().isEqualTo(walk)
            walking.walks().assert().isEqualTo(mapOf(type to walk))
            observed.filterIsInstance<ObservedEvent.AccessWalked>().map { it.aggregates }.assert().containsExactly(2, 1)
            observed.filterIsInstance<ObservedEvent.AccessStamped>().filter { it.cause == Cause.WALK }.sumOf { it.streams }
                .assert().isEqualTo(6)
            // A completed walk is not walked again.
            walking.maintain().assert().isFalse()
            observed.filterIsInstance<ObservedEvent.AccessWalked>().assert().hasSize(2)
        }

    /** [log], counting the reads and writes a walk makes of it. */
    private class Counted(val log: InMemoryEventLog) :
        EventLog by log,
        AccessWalks by log {
        val histories = ArrayList<Int>()
        val bulks = ArrayList<Int>()
        var reads = 0

        override suspend fun histories(refs: List<AggregateRef>) = log.histories(refs).also { histories += refs.size }

        override suspend fun stampAll(stamps: List<AccessStamp>): Int = log.stampAll(
            stamps
        ).also { bulks += stamps.size }

        override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int) =
            log.read(ref, fromVersion, toVersion).also { reads++ }
    }

    @Test
    fun `a page is one read of its histories, their stamps in writes of at most a batch, and one rewrite`(): Unit =
        runBlocking {
            val log = InMemoryEventLog()
            unstamped(log, listOf("d-1", "d-2"), "sales", "legal")
            unstamped(log, listOf("d-3"), "a", "b", "c", "d", "legal")
            val counted = Counted(log)
            val rewritten = ArrayList<Int>()
            val snapshots = object : AccessSnapshots {
                override suspend fun rewrite(ref: AggregateRef, streams: List<EventStreamRecord>, state: CachedState) =
                    error("A page's snapshots are rewritten together.")

                override suspend fun rewriteAll(histories: List<ReplayedHistory>) {
                    rewritten += histories.size
                }
            }
            val walking = AccessWalking(
                "office", "service/office", "n-1", listOf(model), Kernel(counted), { counted }, InMemoryLeaseStore(),
                AccessStampOptions(batch = 4, walkBatch = 3, walkRate = 1_000_000, interval = 1.seconds), snapshots,
                observer = observer,
            )
            walking.maintain().assert().isFalse()
            counted.histories.assert().containsExactly(3)
            counted.reads.assert().isEqualTo(0)
            // d-1 and d-2 together (4 streams), then d-3 in its chunks of at most 4.
            counted.bulks.assert().containsExactly(2, 1, 1)
            rewritten.assert().containsExactly(3)
            listOf("d-1", "d-2").forEach { history(log, it).assert().containsOnly(access("legal")) }
            history(log, "d-3").assert().hasSize(5).containsOnly(access("legal"))
            observed.filterIsInstance<ObservedEvent.AccessStamped>().map { it.streams }.assert().containsExactly(9)
        }

    @Test
    fun `a walk resumes after the page its log records`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        unstamped(log, listOf("d-1", "d-2", "d-3", "d-4"), "sales")
        // Another node walked the first page, then stopped.
        val first = log.aggregates(type, null, 2)
        log.claim(type, AccessWalk(AccessSchema.of(model), heads(log), first.next, walked = 2), expected = null)
        walking(log).maintain()
        val (walked, left) = listOf("d-1", "d-2", "d-3", "d-4").partition { id -> first.refs.any { it.id == id } }
        walked.forEach { history(log, it).assert().containsExactly(null) }
        left.forEach { history(log, it).assert().containsExactly(access("sales")) }
        log.walk(type)!!.walked.assert().isEqualTo(4)
    }

    @Test
    fun `another schema's walk stays while a node of it is left, then this node's schema is claimed`(): Unit =
        runBlocking {
            val log = InMemoryEventLog()
            val leases = InMemoryLeaseStore()
            unstamped(log, listOf("d-1"), "sales")
            val older = "department:integer"
            log.claim(type, AccessWalk(older, heads(log), completed = true), expected = null)
            val members = "service/office#access"
            leases.heartbeat(members, "n-0", "n-0", 30.seconds, setOf("document@${AccessSchema.hashOf(older)}"))
            val walking = walking(log, leases)
            walking.maintain()
            log.walk(type)!!.schema.assert().isEqualTo(older)
            history(log, "d-1").assert().containsExactly(null)

            leases.leave(members, "n-0")
            walking.maintain()
            log.walk(type)!!.let {
                it.schema.assert().isEqualTo(AccessSchema.of(model))
                it.completed.assert().isTrue()
            }
            history(log, "d-1").assert().containsExactly(access("sales"))
        }

    @Test
    fun `the follower stamps a stream without attributes after the walk's heads, and leaves one before them`(): Unit =
        runBlocking {
            val log = InMemoryEventLog()
            unstamped(log, listOf("d-1"), "sales")
            // Claimed up to the heads, not walked yet.
            log.claim(type, AccessWalk(AccessSchema.of(model), heads(log)), expected = null)
            val walking = walking(log)
            // A node of an earlier version, still running, writes after them.
            unstamped(log, listOf("d-2"), "hr", "legal")
            val stamping = AccessStamping("office", listOf(model), Kernel(log), { log }, options, walking, observer)
            listOf("d-1", "d-2").forEach { id ->
                log.readEntries(type.ref("tenant", id)).toList().forEach {
                    stamping.subscription.handle(stamping.subscription.decoder.decode(it.stream, it.position, null))
                }
            }
            history(log, "d-1").assert().containsExactly(null)
            history(log, "d-2").assert().containsExactly(access("legal"), access("legal"))
            // Its whole history at its first stream, once.
            observed.filterIsInstance<ObservedEvent.AccessStamped>().map { it.cause to it.streams }
                .assert().containsExactly(Cause.CATCH_UP to 2)
        }

    @Test
    fun `before any walk is claimed, a stream without attributes is left to the first claim's`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        unstamped(log, listOf("d-1"), "sales")
        val walking = walking(log)
        val position = log.readEntries(type.ref("tenant", "d-1")).toList().single().position
        walking.covers(type, position).assert().isTrue()
        walking.maintain()
        walking.covers(type, position).assert().isTrue()
        history(log, "d-1").assert().containsExactly(access("sales"))
    }

    @Test
    fun `a type without a store of its history is not claimed, and the first run after its first append claims it`(): Unit =
        runBlocking {
            val log = InMemoryEventLog()
            val walking = walking(log)
            walking.maintain().assert().isFalse()
            log.holds(type).assert().isFalse()
            log.walk(type).assert().isNull()
            walking.walks().assert().isEmpty()
            // A node of an earlier version writes the type's first stream: the next run's heads reach it.
            unstamped(log, listOf("d-1"), "sales")
            walking.maintain().assert().isFalse()
            log.walk(type)!!.let {
                it.completed.assert().isTrue()
                it.walked.assert().isEqualTo(1)
            }
            history(log, "d-1").assert().containsExactly(access("sales"))
        }

    /** [log], whose claims, or advances, another node's claim of the schema `other` precedes. */
    private class Contended(
        val log: InMemoryEventLog,
        val claims: Boolean = false,
        val advances: Boolean = false,
    ) : EventLog by log,
        AccessWalks by log {
        override suspend fun claim(type: AggregateType, walk: AccessWalk, expected: String?): Boolean {
            if (claims) log.claim(type, AccessWalk("other", walk.heads), expected)
            return log.claim(type, walk, expected)
        }

        override suspend fun advance(
            type: AggregateType,
            schema: String,
            cursor: String?,
            walked: Long,
            completed: Boolean,
        ): Boolean {
            if (advances) log.claim(type, AccessWalk("other", emptyMap()), schema)
            return log.advance(type, schema, cursor, walked, completed)
        }
    }

    private fun walkingOf(
        log: EventLog,
        leases: InMemoryLeaseStore = InMemoryLeaseStore(),
        options: AccessStampOptions = this.options,
    ) = AccessWalking(
        "office",
        "service/office",
        "n-1",
        listOf(model),
        Kernel(log),
        { log },
        leases,
        options,
        observer = observer,
    )

    @Test
    fun `a run that fails is logged and the next one runs, and stopping leaves the membership`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        unstamped(log, listOf("d-1"), "sales")
        val leases = InMemoryLeaseStore()
        var broken = false
        val walking = AccessWalking(
            "office",
            "service/office",
            "n-1",
            listOf(model),
            Kernel(log),
            { if (broken) error("The log is down.") else log },
            leases,
            AccessStampOptions(walkRate = 1_000_000, interval = 50.milliseconds),
            observer = observer,
        )
        broken = true
        val scope = CoroutineScope(Dispatchers.Default)
        walking.start(scope)
        delay(100)
        log.walk(type).assert().isNull()
        broken = false
        withTimeout(5.seconds) {
            while (log.walk(type)?.completed != true) delay(10)
        }
        leases.members("service/office#access").map { it.node }.assert().containsExactly("n-1")
        walking.stop()
        leases.members("service/office#access").assert().isEmpty()
        scope.cancel()
        walking.toString().assert().isEqualTo("AccessWalking(office)")
    }

    @Test
    fun `a walk past its run's deadline is left for the next run, which goes on from its cursor`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        unstamped(log, listOf("d-1", "d-2", "d-3"), "sales")
        val walking = walkingOf(
            log,
            options = AccessStampOptions(walkBatch = 1, walkRate = 10, interval = 50.milliseconds)
        )
        walking.maintain().assert().isTrue()
        log.walk(type)!!.walked.assert().isLessThan(3)
        var runs = 1
        while (walking.maintain()) runs++
        runs.assert().isGreaterThan(1)
        log.walk(type)!!.let {
            it.completed.assert().isTrue()
            it.walked.assert().isEqualTo(3)
        }
        listOf("d-1", "d-2", "d-3").forEach { history(log, it).assert().containsExactly(access("sales")) }
    }

    @Test
    fun `a claim lost to another node takes its walk, and a walk whose claim moved meanwhile stops`(): Unit =
        runBlocking {
            val log = InMemoryEventLog()
            unstamped(log, listOf("d-1", "d-2", "d-3"), "sales")
            val lost = walkingOf(Contended(log, claims = true))
            lost.maintain().assert().isFalse()
            lost.walks().getValue(type).schema.assert().isEqualTo("other")
            // No node serves `other`: this node's schema is claimed in its place, and moves again after a page.
            val moved = walkingOf(Contended(log, advances = true))
            moved.maintain().assert().isFalse()
            log.walk(type)!!.schema.assert().isEqualTo("other")
            moved.walks().getValue(type).schema.assert().isEqualTo("other")
        }

    @Test
    fun `a walk another node leads is left to it`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        unstamped(log, listOf("d-1"), "sales")
        val leases = InMemoryLeaseStore()
        leases.acquire("service/office#access-walk/document", 0, "n-0", 30.seconds)
        walkingOf(log, leases).maintain().assert().isFalse()
        log.walk(type)!!.walked.assert().isEqualTo(0)
        history(log, "d-1").assert().containsExactly(null)
    }

    @Test
    fun `a log that keeps no walks has none, and its streams are no walk's`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        unstamped(log, listOf("d-1"), "sales")
        val plain = object : EventLog by log {}
        val walking = walkingOf(plain)
        walking.maintain().assert().isFalse()
        walking.walks().assert().isEmpty()
        val position = log.readEntries(type.ref("tenant", "d-1")).toList().single().position
        walking.covers(type, position).assert().isFalse()
        walking.stampCurrent(model, type.ref("tenant", "d-1"), Cause.CATCH_UP).assert().isNull()
        walking.stop()
    }

    @Test
    fun `the follower reads a walk once, none claimed below the head it read, one claimed elsewhere by its heads`(): Unit =
        runBlocking {
            val log = InMemoryEventLog()
            unstamped(log, listOf("d-1"), "sales")
            val position = log.readEntries(type.ref("tenant", "d-1")).toList().single().position
            val walking = walking(log)
            walking.covers(type, null).assert().isFalse()
            walking.covers(type, position).assert().isTrue()
            // Known to be below the head read before no walk was found.
            walking.covers(type, position).assert().isTrue()
            // Claimed by another node before this stream: the follower's to stamp.
            log.claim(type, AccessWalk(AccessSchema.of(model), mapOf(position.partition to 0L)), expected = null)
            walking(log).covers(type, position).assert().isFalse()
            // An aggregate without a stream has nothing to stamp.
            walking.stampCurrent(model, type.ref("tenant", "none"), Cause.CATCH_UP).assert().isNull()
        }

    enum class Tier { GOLD }

    class Shelf(val id: String) {
        var tier: Tier? = null
        var open: Boolean = false
        var size: Int = 0
        val keepers: MutableList<String> = ArrayList()
    }

    @Test
    fun `an access schema names each attribute with the kind of its stored values, in name order`() {
        val shelf = aggregate<Shelf>("office", "shelf") {
            initial { Shelf(it.id) }
            access(AccessAttribute.single<Shelf, Tier>("tier", Tier::class.java)) { it.tier }
            access(AccessAttribute.single<Shelf, Boolean>("open", Boolean::class.javaObjectType)) { it.open }
            access(AccessAttribute.single<Shelf, Int>("size", Int::class.javaObjectType)) { it.size }
            access(AccessAttribute.multiple<Shelf, String>("keepers", String::class.java)) { it.keepers }
        }
        val schema = AccessSchema.of(shelf)
        schema.assert().isEqualTo("keepers:string[],open:boolean,size:integer,tier:enum")
        AccessSchema.hashOf(schema).assert().hasSize(16).isEqualTo(AccessSchema.hashOf(schema))
        AccessSchema.of(aggregate<Shelf>("office", "bare") { initial { Shelf(it.id) } }).assert().isEmpty()
    }

    @Test
    fun `a walk stamps the other stores of a type's history too, a store of another context's type not`(): Unit =
        runBlocking {
            val log = InMemoryEventLog()
            unstamped(log, listOf("d-1"), "sales", "legal")
            val stamped = ArrayList<Triple<AggregateRef, Map<String, Any?>, Int>>()
            val index = object : AccessStamps {
                override suspend fun stamp(
                    ref: AggregateRef,
                    access: Map<String, Any?>,
                    asOf: Int,
                    from: Int,
                    until: Int,
                ): Int {
                    stamped += Triple(ref, access, asOf)
                    return until - from
                }
            }
            val walking = walking(log)
            walking.target(type, index)
            walking.target(AggregateType("hr", "document"), index)
            walking.maintain()
            stamped.assert().containsExactly(Triple(type.ref("tenant", "d-1"), access("legal"), 2))
        }
}
