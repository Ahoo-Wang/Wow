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
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Rewrites the snapshot of an aggregate from its whole log ([EventStreamRecord]s from version 1), already replayed as
 * its state: the access walk's share of the snapshots, which carry the attributes of the state they hold.
 */
@InternalWowApi
fun interface AccessSnapshots {
    suspend fun rewrite(ref: AggregateRef, streams: List<EventStreamRecord>, state: CachedState)

    /** [rewrite]s the snapshot of each of [histories], aggregates of one type: a walk's page, in as few writes as it can. */
    suspend fun rewriteAll(histories: List<ReplayedHistory>) {
        histories.forEach { rewrite(it.ref, it.streams, it.state) }
    }

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
 *   store is not created: the first run after its first append claims it.
 * - **The walk**: the node that holds the type's walk lease (`<group>#access-walk/<aggregate>`) pages its aggregates
 *   ([AccessWalks.aggregates], [AccessStampOptions.walkBatch] a page) and stamps each one's whole history with its
 *   current attributes, at most [AccessStampOptions.walkRate] aggregates a second. A page is a few writes, whatever
 *   its size: one read of its histories ([AccessWalks.histories]), their stamps together, at most
 *   [AccessStampOptions.batch] streams a write ([AccessStamps.stampAll]), in the log and in each other store of the
 *   history ([target]), and one rewrite of their snapshots ([AccessSnapshots.rewriteAll]). After each page it records
 *   its cursor and count, so another node or a restart resumes it there; past the last page it is completed.
 * - **After the heads**: the walk reaches every stream up to its heads. A stream after them that carries no attributes
 *   (a node of an earlier version still running, an import) is the follower's: [AccessStamping] stamps its aggregate
 *   when it reads it ([covers]).
 *
 * The walks are observable in the registry of each log (the walk's schema, heads, cursor, count and completion), in
 * the observer's [ObservedEvent.AccessWalked] and [ObservedEvent.AccessStamped], and in the log.
 */
@Suppress("LongParameterList", "TooManyFunctions")
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

    @Volatile
    private var loop: Job? = null

    /** The walk of each type, as this node last read or claimed it. */
    fun walks(): Map<AggregateType, AccessWalk> = walked.toMap()

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
    ): Stamped? {
        val log = logOf(ref.type) ?: return null
        val stamps = log as? AccessStamps ?: return null
        val started = time.markNow()
        val streams = log.read(ref).toList()
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
     * Stamps the whole history of each of [refs], a page of [model]'s aggregates, as [stampCurrent] stamps one: one
     * read of their histories, their stamps written together in [bulksOf], in the log and in each other store
     * ([target]), and one rewrite of their snapshots.
     */
    private suspend fun stampPage(model: AggregateModel<*>, walks: AccessWalks, refs: List<AggregateRef>) {
        if (refs.isEmpty()) {
            return
        }
        val started = time.markNow()
        val replayed = walks.histories(refs).mapNotNull { (ref, streams) ->
            kernel.replayed(model, ref, streams)?.let { ReplayedHistory(ref, streams, it) }
        }
        val stamps = replayed.flatMap { history ->
            val head = history.streams.last().version
            val access = AccessValues.ofStream(kernel.accessOf(model, history.state.state), history.state.scope)
            (1..head step options.batch).map { from ->
                AccessStamp(history.ref, access, head, from, minOf(from + options.batch, head + 1))
            }
        }
        val bulks = bulksOf(stamps)
        val written = bulks.sumOf { walks.stampAll(it) }
        targets[model.type]?.forEach { target -> bulks.forEach { target.stampAll(it) } }
        snapshots.rewriteAll(replayed)
        val millis = started.elapsedNow().inWholeMilliseconds
        observer.observed(
            ObservedEvent.AccessStamped(model.type, ObservedEvent.AccessStamped.Cause.WALK, written, millis)
        )
    }

    /** [stamps] in writes of at most [AccessStampOptions.batch] streams each, a stamp's chunk whole in one. */
    private fun bulksOf(stamps: List<AccessStamp>): List<List<AccessStamp>> {
        val bulks = ArrayList<List<AccessStamp>>()
        var bulk = ArrayList<AccessStamp>()
        var streams = 0
        stamps.forEach { stamp ->
            val size = stamp.until - stamp.from
            if (bulk.isNotEmpty() && streams + size > options.batch) {
                bulks += bulk
                bulk = ArrayList()
                streams = 0
            }
            bulk += stamp
            streams += size
        }
        if (bulk.isNotEmpty()) {
            bulks += bulk
        }
        return bulks
    }

    /** Keeps this node a member of the context's walks, serving its schema of each type. */
    private suspend fun heartbeat() {
        if (schemas.isNotEmpty()) {
            leases.heartbeat(members, node, address(), options.memberTtl, served)
        }
    }

    /** Reads the walk of each type, claiming it for this node's schema as the class says. */
    private suspend fun claims() {
        schemas.forEach { (type, schema) ->
            if (walked[type]?.let { it.schema == schema && it.completed } == true) {
                return@forEach
            }
            val walks = checkNotNull(walksOf(type))
            // A type without a store of its history has nothing to walk, and its store is its first append's to
            // create: the first run after it claims the walk, with heads that reach it.
            if (!walks.holds(type)) {
                return@forEach
            }
            val recorded = walks.walk(type)
            // Another schema's walk stays while a node of it is left.
            if (recorded != null && (recorded.schema == schema || hasMembers(type, recorded.schema))) {
                walked[type] = recorded
                return@forEach
            }
            val heads = headsOf(type)
            // An empty log holds nothing to walk.
            val walk = AccessWalk(schema, heads, completed = heads.values.all { it == 0L })
            if (walks.claim(type, walk, recorded?.schema)) {
                walked[type] = walk
                logger.info { "The access walk of $type is claimed for the schema [$schema], up to the heads $heads." }
            } else {
                walks.walk(type)?.let { walked[type] = it }
            }
        }
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
        var walk = start
        while (!deadline.hasPassedNow()) {
            val began = time.markNow()
            val page = walks.aggregates(type, walk.cursor, options.walkBatch)
            stampPage(model, walks, page.refs)
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
            // At most walkRate aggregates a second.
            val least = (page.refs.size * NANOS_PER_SECOND / options.walkRate).nanoseconds
            delay(least - began.elapsedNow())
        }
        return true
    }

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
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private val logger = KotlinLogging.logger {}
    }
}

/** An aggregate's whole log, [streams] from version 1, replayed as [state]: a walk's, to stamp it and rewrite its snapshot. */
@InternalWowApi
class ReplayedHistory(val ref: AggregateRef, val streams: List<EventStreamRecord>, val state: CachedState)

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
