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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CachedState
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.runtime.state.ReplayGate
import me.ahoo.wow.spi.StorageBusyException
import me.ahoo.wow.spi.access.AccessStamp
import me.ahoo.wow.spi.access.AccessStamps
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.access.AccessWalk
import me.ahoo.wow.spi.access.AccessWalks
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.subscription.LeaseStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Rewrites the snapshot of an aggregate from its whole log ([EventStreamRecord]s from version 1), already replayed as
 * its state: the access walk's share of the snapshots, which carry the attributes of the state they hold.
 */
@InternalWowApi
fun interface AccessSnapshots {
    suspend fun rewrite(ref: AggregateRef, streams: List<EventStreamRecord>, state: CachedState)

    @InternalWowApi
    companion object {
        /** No snapshots to rewrite. */
        val NONE: AccessSnapshots = AccessSnapshots { _, _, _ -> }
    }
}

/**
 * The access walks of one bounded context, on every node, every [AccessStampOptions.interval]: each aggregate type's
 * history stamped with the attributes of its aggregates' current states, where no commit stamped it with the type's
 * current access schema ([AccessSchema]): a log an earlier version wrote, an import, an attribute the model declares
 * since.
 *
 * - **Claims**: the type's log records one walk of the type ([AccessWalks.walk]), of an access schema, with the log's
 *   heads when it was claimed. A node claims its own schema's when none is recorded, and in place of another schema's
 *   once no node of that one is left (each node is a member of `<group>#access`, serving `<aggregate>@<schema hash>`
 *   for its schema of each type): a rolling deployment that changes the schema starts the walk once the last node of
 *   the old one stopped, so the walk reaches what that node wrote. A walk of this node's schema, once completed, is
 *   not read again. A type whose log holds no store of its history yet ([AccessWalks.holds]) is not claimed, and its
 *   store is not created. The step that creates it records the walk completed for this node's schema, which the
 *   node's assembly tells each log ([serve]), since every stream it then holds was committed with its attributes; a
 *   store another writer created (an import, a tool) records none, and the first run after its first append claims
 *   its walk.
 * - **The walk**: the node that holds the type's walk lease (`<group>#access-walk/<aggregate>`) pages its aggregates
 *   ([AccessWalks.aggregates], at most [AccessStampOptions.walkBatch] a page) and stamps each one's whole history with
 *   its current attributes ([stampCurrent]), one aggregate after another, each in statements of its own. After each
 *   page it records its cursor and count, so another node or a restart resumes it there; past the last page it is
 *   completed.
 * - **Its pace**: the walk is background work on the store the commands write to, so it takes a bounded share of it
 *   by construction ([WalkPace]): after each aggregate it waits so that its stamping took at most
 *   [AccessStampOptions.walkShare] of the time, and so that it stamps at most [AccessStampOptions.walkRate] a second.
 *   Its own statements slow down as the store's load rises, from any node, and the pace with them. Each second it
 *   reads whether the log store's writes pressed ([ReplayGate.pressed]): the share halves while they do, down to an
 *   eighth, and comes back an eighth a second while they do not. Its history reads run under the log store's query
 *   bulkhead ([ReplayGate.replay]); one the bulkhead refuses is a press too, and the aggregate is read again. A page
 *   holds as many aggregates as the last pace stamps in an interval, so the walk records its cursor about every
 *   interval, and it renews its lease within a page.
 * - **After the heads**: the walk reaches every stream up to its heads. A stream after them that carries no attributes
 *   (a node of an earlier version still running, an import) is the follower's: [AccessStamping] stamps its aggregate
 *   when it reads it ([covers]).
 *
 * The walks are observable in the registry of each log (the walk's schema, heads, cursor, count and completion), in
 * the observer's [ObservedEvent.AccessWalked] and [ObservedEvent.AccessStamped], and in the log.
 */
@InternalWowApi
class AccessWalking(
    val context: String,
    private val group: String,
    private val node: String,
    models: Collection<AggregateModel<*>>,
    private val kernel: Kernel,
    /** The log of each type's history, its log group's own; none for a type of another context. */
    private val logOf: (AggregateType) -> EventLog?,
    private val leases: LeaseStore,
    private val options: AccessStampOptions = AccessStampOptions(),
    private val snapshots: AccessSnapshots = AccessSnapshots.NONE,
    private val address: () -> String = { node },
    private val observer: Observer = Observer.NONE,
    /** Where the walk's history reads run, and whether the log store's writes are pressed. */
    private val gate: ReplayGate = ReplayGate.OPEN,
    private val time: TimeSource = TimeSource.Monotonic,
) {
    private val models: Map<AggregateType, AggregateModel<*>> = models.associateBy { it.type }

    /** This node's access schema of each type whose log keeps walks. */
    private val schemas: Map<AggregateType, String> =
        this.models.filterKeys { walksOf(it) != null }.mapValues { (_, model) -> AccessSchema.of(model) }

    /** The membership of the nodes of the context, each serving its schema of each type ([servedOf]). */
    private val members = "$group#access"
    private val served: Set<String> = schemas.mapTo(LinkedHashSet()) { (type, schema) -> servedOf(type, schema) }

    /** The walk of each type as last read or claimed. */
    private val walked = ConcurrentHashMap<AggregateType, AccessWalk>()

    /** The other stores of each type's history ([target]): the indices of its streams. */
    private val targets = ConcurrentHashMap<AggregateType, MutableList<AccessStamps>>()

    /** While no walk of a type is recorded: the head of a partition read before the last read found none. */
    private val unclaimed = ConcurrentHashMap<Pair<AggregateType, Int>, Long>()

    /** The pace of each type's walk on this node. */
    private val paces = ConcurrentHashMap<AggregateType, WalkPace>()

    /** The types this node found without a store, read again after fewer runs each time. */
    private val absent = AbsentTypes()

    @Volatile
    private var loop: Job? = null

    /** The walk of each type, as this node last read or claimed it. */
    fun walks(): Map<AggregateType, AccessWalk> = walked.toMap()

    /** The share of the time the walk of [type] takes now on this node ([WalkPace.share]); null before it walked here. */
    fun share(type: AggregateType): Double? = paces[type]?.share

    /**
     * Adds [stamps], another store of [type]'s history (an index of its streams, which a sink writes and follows the
     * changes of itself), to what [stampCurrent] stamps: a walk reaches what it wrote before.
     */
    fun target(type: AggregateType, stamps: AccessStamps) {
        if (type in models) {
            targets.computeIfAbsent(type) { CopyOnWriteArrayList() } += stamps
        }
    }

    /**
     * Runs [maintain] in [scope], at once and again at once while it leaves a walk of this node, else after an
     * interval. Until the first run claims them, the follower leaves the streams without attributes to the walks
     * ([covers]).
     */
    fun start(scope: CoroutineScope) {
        loop = scope.launch {
            while (isActive) {
                val walking = try {
                    maintain()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (
                    @Suppress("TooGenericExceptionCaught") // A run that fails is logged; the next one tries again.
                    failure: Exception,
                ) {
                    logger.warn(failure) { "The access walks of $context failed; they run again." }
                    false
                }
                if (!walking) {
                    delay(options.interval)
                }
            }
        }
    }

    /** Stops the runs, releases the walk leases this node holds and leaves its schema memberships. */
    suspend fun stop() {
        loop?.cancel()
        loop = null
        schemas.keys.forEach { leases.release(walkGroup(it), 0, node) }
        if (schemas.isNotEmpty()) {
            leases.leave(members, node)
        }
    }

    /**
     * One run: memberships, claims, then the walks this node leads, for at most an interval; whether one of them is
     * left for the next run.
     */
    suspend fun maintain(): Boolean {
        heartbeat()
        claims()
        val deadline = time.markNow() + options.interval
        var walking = false
        for ((type, schema) in schemas) {
            val walk = walked[type]?.takeIf { it.schema == schema && !it.completed } ?: continue
            if (deadline.hasPassedNow()) {
                break
            }
            walking = walk(type, walk, deadline) || walking
        }
        return walking
    }

    /**
     * Whether the walk of [type] stamps the stream at [position]: the recorded walk's heads reach it, or no walk is
     * recorded yet, whose claim reads heads that will. A stream it does not reach is the follower's to stamp.
     */
    suspend fun covers(type: AggregateType, position: LogPosition?): Boolean {
        val walks = walksOf(type) ?: return false
        position ?: return false
        walked[type]?.let { return position.sequence <= (it.heads[position.partition] ?: 0) }
        val key = type to position.partition
        if (position.sequence <= (unclaimed[key] ?: 0)) {
            return true
        }
        // The head first: a claim after the read of no walk reads the heads after it.
        val head = logOf(type)?.head(position.partition) ?: 0
        val walk = walks.walk(type)
        if (walk != null) {
            walked[type] = walk
            return position.sequence <= (walk.heads[position.partition] ?: 0)
        }
        unclaimed[key] = head
        return position.sequence <= head
    }

    /**
     * Stamps the whole history of [ref], of [model], with the attributes of its current state, as of its last version,
     * in its log and its other stores ([target]), and rewrites its snapshot; null when its log keeps no stamps or
     * holds none of it.
     */
    suspend fun stampCurrent(
        model: AggregateModel<*>,
        ref: AggregateRef,
        cause: ObservedEvent.AccessStamped.Cause,
    ): Stamped? = stampCurrent(model, ref, cause) { log -> log.read(ref).toList() }

    /** [stampCurrent], reading [ref]'s whole history from its log with [history]. */
    private suspend fun stampCurrent(
        model: AggregateModel<*>,
        ref: AggregateRef,
        cause: ObservedEvent.AccessStamped.Cause,
        history: suspend (EventLog) -> List<EventStreamRecord>,
    ): Stamped? {
        val log = logOf(ref.type) ?: return null
        val stamps = log as? AccessStamps ?: return null
        val started = time.markNow()
        val streams = history(log)
        val state = kernel.replayed(model, ref, streams) ?: return null
        val head = streams.last().version
        val access = AccessValues.ofStream(kernel.accessOf(model, state.state), state.scope)
        val written = stamps.stampIn(ref, access, head, 1, head + 1, options.batch)
        targets[ref.type]?.forEach { it.stampIn(ref, access, head, 1, head + 1, options.batch) }
        snapshots.rewrite(ref, streams, state)
        val millis = started.elapsedNow().inWholeMilliseconds
        observer.observed(ObservedEvent.AccessStamped(ref.type, cause, written, millis))
        return Stamped(head, access)
    }

    /**
     * Tells each type's log the access schema this node serves for it ([AccessWalks.serves]), before the log creates
     * any of their stores: a store it creates then records its walk completed. The node's assembly calls it.
     */
    fun serve() {
        schemas.keys.groupBy { checkNotNull(walksOf(it)) }.forEach { (walks, types) ->
            walks.serves(types.associateWith { schemas.getValue(it) })
        }
    }

    /**
     * Makes this node a member of the context's walks before it accepts commands, so a node of another schema reads
     * it among them ([claim]) before it appends; its runs keep it one.
     */
    suspend fun enter() = heartbeat()

    /** Keeps this node a member of the context's walks, serving its schema of each type. */
    private suspend fun heartbeat() {
        if (schemas.isNotEmpty()) {
            leases.heartbeat(members, node, address(), options.memberTtl, served)
        }
    }

    /** Reads the walk of each type, claiming it for this node's schema as the class says. */
    private suspend fun claims() {
        schemas.forEach { (type, schema) ->
            if (walked[type]?.let { it.schema == schema && it.completed } != true) {
                claim(type, schema)
            }
        }
    }

    /** Reads the walk of [type], claiming it for [schema] as the class says. */
    private suspend fun claim(type: AggregateType, schema: String) {
        val walks = checkNotNull(walksOf(type))
        // A type without a store of its history has nothing to walk, and its store is its first append's to create:
        // the first run due after it claims the walk, with heads that reach it, unless the creation recorded it.
        if (!absent.due(type)) {
            return
        }
        if (!walks.holds(type)) {
            absent.absent(type)
            return
        }
        absent.found(type)
        val recorded = walks.walk(type)
        // Another schema's walk stays while a node of it is left.
        if (recorded != null && (recorded.schema == schema || hasMembers(type, recorded.schema))) {
            walked[type] = recorded
            return
        }
        record(type, walks, schema, recorded?.schema)
    }

    /** Claims the walk of [type] for [schema] in place of [expected]'s, up to the log's heads: completed when empty. */
    private suspend fun record(type: AggregateType, walks: AccessWalks, schema: String, expected: String?) {
        val heads = headsOf(type)
        val walk = AccessWalk(schema, heads, completed = heads.values.all { it == 0L })
        if (!walks.claim(type, walk, expected)) {
            walks.walk(type)?.let { walked[type] = it }
            return
        }
        walked[type] = walk
        logger.info { "The access walk of $type is claimed for the schema [$schema], up to the heads $heads." }
    }

    /**
     * Walks [type] from [start] under its walk lease, a page at a time, until [deadline] or its last page; whether the
     * walk is left for the next run.
     */
    private suspend fun walk(type: AggregateType, start: AccessWalk, deadline: TimeMark): Boolean {
        val lease = walkGroup(type)
        leases.acquire(lease, 0, node, options.leaseTtl) ?: return false
        val walks = checkNotNull(walksOf(type))
        val model = models.getValue(type)
        val pace = paces.computeIfAbsent(type) { WalkPace(options.walkShare, options.walkRate, time) }
        var walk = start
        var renewed = time.markNow()
        while (!deadline.hasPassedNow()) {
            val page = walks.aggregates(type, walk.cursor, pace.pageOf(options.walkBatch, options.interval))
            page.refs.forEach { ref ->
                stampPaced(model, ref, pace)
                // A slow pace may hold a page longer than a share of the lease: it is renewed within the page.
                if (renewed.elapsedNow() >= options.leaseTtl / LEASE_RENEWALS) {
                    leases.renew(lease, listOf(0), node, options.leaseTtl)
                    renewed = time.markNow()
                }
            }
            val next = walk.copy(
                cursor = page.next ?: walk.cursor,
                walked = walk.walked + page.refs.size,
                completed = page.next == null,
            )
            if (!walks.advance(type, walk.schema, next.cursor, next.walked, next.completed)) {
                // Another schema was claimed meanwhile: its walk starts over.
                walks.walk(type)?.let { walked[type] = it }
                leases.release(lease, 0, node)
                return false
            }
            walk = next
            walked[type] = walk
            observer.observed(ObservedEvent.AccessWalked(type, page.refs.size, walk.completed))
            if (walk.completed) {
                leases.release(lease, 0, node)
                logger.info { "The access walk of $type is completed: ${walk.walked} aggregates stamped." }
                return false
            }
            leases.renew(lease, listOf(0), node, options.leaseTtl)
            renewed = time.markNow()
        }
        return true
    }

    /**
     * Stamps [ref] as the walk does, its history read under the log store's query bulkhead, then waits as [pace] says;
     * a read the bulkhead refuses presses the pace, and is tried again a period later.
     */
    @Suppress("TooGenericExceptionCaught") // Only a refusal is the walk's to wait out; the rest is rethrown.
    private suspend fun stampPaced(model: AggregateModel<*>, ref: AggregateRef, pace: WalkPace) {
        while (true) {
            pace.sense { gate.pressed(ref.type) }
            val began = time.markNow()
            try {
                stampCurrent(model, ref, ObservedEvent.AccessStamped.Cause.WALK) { log ->
                    gate.replay(ref) { log.read(ref).toList() }
                }
            } catch (failure: Exception) {
                if (!refused(failure)) throw failure
                logger.debug(failure) { "The log store refused the access walk's read of $ref." }
                pace.press()
                delay(WalkPace.PERIOD)
                continue
            }
            delay(pace.pause(began.elapsedNow()))
            return
        }
    }

    /** Whether the store did not admit the call: a bound throws [StorageBusyException] as the cause of its own error. */
    private fun refused(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }
            .take(MAX_CAUSES)
            .any { it is StorageBusyException }

    private suspend fun hasMembers(type: AggregateType, schema: String): Boolean =
        leases.members(members).any { servedOf(type, schema) in it.groups }

    private suspend fun headsOf(type: AggregateType): Map<Int, Long> {
        val log = checkNotNull(logOf(type))
        return (0 until log.partitions).associateWith { log.head(it) ?: 0L }
    }

    private fun walksOf(type: AggregateType): AccessWalks? = logOf(type) as? AccessWalks

    private fun servedOf(type: AggregateType, schema: String): String = "${type.name}@${AccessSchema.hashOf(schema)}"

    private fun walkGroup(type: AggregateType): String = "$group#access-walk/${type.name}"

    override fun toString(): String = "AccessWalking($context)"

    @InternalWowApi
    companion object {
        /** The renewals of a walk's lease within its time to live, at the least. */
        private const val LEASE_RENEWALS = 3

        /** How deep a failure's causes are searched for a refusal. */
        private const val MAX_CAUSES = 8
        private val logger = KotlinLogging.logger {}
    }
}

/** The attributes of an aggregate's history as of [version]: what a stamp wrote, or a stream carries. */
@InternalWowApi
class Stamped(val version: Int, val access: Map<String, Any?>)

/**
 * The access schema of an aggregate type: what its streams' attributes are derived as, the declared ones by name, each
 * with the kind of its stored values and whether it holds many (`department:string,teams:string[]`), empty for none.
 * The built-in owner and space are of every schema.
 */
@InternalWowApi
object AccessSchema {
    fun of(model: AggregateModel<*>): String = model.accessAttributes.sortedBy { it.name }.joinToString(",") {
        "${it.name}:${kindOf(it.type)}${if (it.multiple) "[]" else ""}"
    }

    /** A short hash of [schema], for a name. */
    fun hashOf(schema: String): String =
        MessageDigest.getInstance("SHA-256").digest(schema.toByteArray()).take(HASH_BYTES)
            .joinToString("") { "%02x".format(it) }

    private fun kindOf(type: Class<*>): String = when {
        type.isEnum -> "enum"
        type == String::class.java -> "string"
        type == Boolean::class.javaObjectType -> "boolean"
        else -> "integer"
    }

    private const val HASH_BYTES = 8
}

/**
 * Writes [stamps] together ([AccessStamps.stampAll]), in writes of at most [batch] streams each: a stamp of more is cut
 * into chunks of [batch], each written whole in one write. Returns the streams stamped.
 */
@InternalWowApi
suspend fun AccessStamps.stampAllIn(stamps: List<AccessStamp>, batch: Int): Int {
    require(batch >= 1) { "A write stamps at least one stream." }
    val chunks = stamps.flatMap { stamp ->
        (stamp.from until stamp.until step batch).map { from ->
            stamp.copy(from = from, until = minOf(from + batch, stamp.until))
        }
    }
    var written = 0
    var bulk = ArrayList<AccessStamp>()
    var streams = 0
    chunks.forEach { chunk ->
        val size = chunk.until - chunk.from
        if (bulk.isNotEmpty() && streams + size > batch) {
            written += stampAll(bulk)
            bulk = ArrayList()
            streams = 0
        }
        bulk += chunk
        streams += size
    }
    if (bulk.isNotEmpty()) {
        written += stampAll(bulk)
    }
    return written
}

/** Stamps [access] as of [asOf] over the streams of [ref] from [from] until [until], at most [batch] in each stamp. */
internal suspend fun AccessStamps.stampIn(
    ref: AggregateRef,
    access: Map<String, Any?>,
    asOf: Int,
    from: Int,
    until: Int,
    batch: Int,
): Int {
    var start = from
    var written = 0
    while (start < until) {
        val end = minOf(start + batch, until)
        written += stamp(ref, access, asOf, start, end)
        start = end
    }
    return written
}
