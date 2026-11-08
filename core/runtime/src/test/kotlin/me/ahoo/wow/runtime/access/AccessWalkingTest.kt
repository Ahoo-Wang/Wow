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
import kotlinx.coroutines.flow.Flow
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
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.runtime.state.ReplayGate
import me.ahoo.wow.spi.StorageBusyException
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
import me.ahoo.wow.spi.subscription.LeaseStore
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
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
        leases: LeaseStore = InMemoryLeaseStore(),
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

    /** [log], recording the reads and stamps the walk makes, in order, by aggregate id. */
    private class Recording(val log: InMemoryEventLog) :
        EventLog by log,
        AccessWalks by log {
        val calls = CopyOnWriteArrayList<String>()

        override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> {
            calls += "read ${ref.id}"
            return log.read(ref, fromVersion, toVersion)
        }

        override suspend fun stamp(ref: AggregateRef, access: Map<String, Any?>, asOf: Int, from: Int, until: Int): Int {
            calls += "stamp ${ref.id}"
            return log.stamp(ref, access, asOf, from, until)
        }

        override suspend fun stampAll(stamps: List<AccessStamp>): Int {
            calls += "stampAll"
            return log.stampAll(stamps)
        }
    }

    /** A gate that counts the walk's reads by aggregate id, refuses the first [refusals] and says whether [pressed]. */
    private class Gate(var refusals: Int = 0, val pressed: Boolean = false) : ReplayGate {
        val replayed = CopyOnWriteArrayList<String>()

        override suspend fun <T> replay(ref: AggregateRef, read: suspend () -> T): T {
            if (refusals > 0) {
                refusals--
                throw StorageBusyException("The log store runs as many queries as it allows at once.")
            }
            replayed += ref.id
            return read()
        }

        override fun pressed(type: AggregateType): Boolean = pressed
    }

    private fun gated(log: EventLog, gate: ReplayGate, options: AccessStampOptions = this.options) = AccessWalking(
        "office",
        "service/office",
        "n-1",
        listOf(model),
        Kernel(log),
        { log },
        InMemoryLeaseStore(),
        options,
        observer = observer,
        gate = gate,
    )

    @Test
    fun `a walk reads and stamps one aggregate after another, each in statements of its own, never a page at once`(): Unit =
        runBlocking {
            val log = Recording(InMemoryEventLog())
            unstamped(log.log, listOf("d-1", "d-2", "d-3"), "sales", "legal")
            walkingOf(log).maintain().assert().isFalse()
            log.calls.assert().containsExactly(
                "read d-1",
                "stamp d-1",
                "read d-2",
                "stamp d-2",
                "read d-3",
                "stamp d-3",
            )
            listOf(
                "d-1",
                "d-2",
                "d-3"
            ).forEach { history(log.log, it).assert().containsExactly(access("legal"), access("legal")) }
        }

    @Test
    fun `a walk reads each history under the replay gate, and gives way while the writes press`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        unstamped(log, listOf("d-1", "d-2"), "sales")
        val gate = Gate(pressed = true)
        val walking = gated(log, gate)
        walking.share(type).assert().isNull()
        walking.maintain().assert().isFalse()
        gate.replayed.assert().containsExactly("d-1", "d-2")
        // Sensed once in the run's first second: halved.
        walking.share(type).assert().isEqualTo(AccessStampOptions.DEFAULT_WALK_SHARE / 2)
        listOf("d-1", "d-2").forEach { history(log, it).assert().containsExactly(access("sales")) }
        // The follower's catch-up reads its history directly.
        walking.stampCurrent(model, type.ref("tenant", "d-1"), Cause.CATCH_UP)
        gate.replayed.assert().containsExactly("d-1", "d-2")
    }

    @Test
    fun `a read the gate refuses presses the walk, which reads that aggregate again and goes on`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        unstamped(log, listOf("d-1", "d-2"), "sales")
        val gate = Gate(refusals = 1)
        val walking = gated(log, gate, AccessStampOptions(walkBatch = 2, walkRate = 1_000_000, walkShare = 0.8))
        walking.maintain().assert().isFalse()
        gate.replayed.assert().containsExactly("d-1", "d-2")
        log.walk(type)!!.let {
            it.completed.assert().isTrue()
            it.walked.assert().isEqualTo(2)
        }
        // Halved by the refusal; the next second's calm sense adds back an eighth.
        walking.share(type)!!.assert().isLessThan(0.8)
        listOf("d-1", "d-2").forEach { history(log, it).assert().containsExactly(access("sales")) }
    }

    @Test
    fun `a walk ends however long the writes press, at an eighth of its share`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        val ids = (1..6).map { "d-$it" }
        unstamped(log, ids, "sales")
        val walking =
            gated(log, Gate(pressed = true), AccessStampOptions(walkBatch = 2, walkRate = 1_000_000, walkShare = 1.0))
        withTimeout(10.seconds) {
            while (log.walk(type)?.completed != true) walking.maintain()
        }
        walking.share(type)!!.assert().isBetween(1.0 / 8, 0.5)
        ids.forEach { history(log, it).assert().containsExactly(access("sales")) }
    }

    @Test
    fun `a page that takes longer than a third of the lease renews the lease within it`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        unstamped(log, listOf("d-1", "d-2", "d-3"), "sales")
        val leases = InMemoryLeaseStore()
        // The aggregates the walk had stamped at each renewal of its lease.
        val renewals = CopyOnWriteArrayList<Int>()
        val renewing = object : LeaseStore by leases {
            override suspend fun renew(group: String, partitions: Collection<Int>, owner: String, ttl: Duration) =
                leases.renew(group, partitions, owner, ttl).also {
                    renewals += observed.count { it is ObservedEvent.AccessStamped && it.cause == Cause.WALK }
                }
        }
        // 10 aggregates a second, a page of three: a third of the 240 ms lease passes within the page.
        val options = AccessStampOptions(
            walkBatch = 3,
            walkRate = 10,
            interval = 200.milliseconds,
            leaseTtl = 240.milliseconds,
        )
        val walking = walkingOf(log, options = options, leases = renewing)
        withTimeout(10.seconds) {
            while (walking.maintain()) continue
        }
        log.walk(type)!!.completed.assert().isTrue()
        renewals.first().assert().isLessThan(3)
    }

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
