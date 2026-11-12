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

package me.ahoo.wow.migrate

import kotlinx.coroutines.flow.first
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.access.AccessWalk
import me.ahoo.wow.spi.access.AccessWalk.Companion.IMPORTING
import me.ahoo.wow.spi.access.AccessWalks
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import java.util.concurrent.ConcurrentHashMap

/**
 * Appends the streams of one 9.x instance to the V10 log, as the full import and the tail ([EventStreamImporter])
 * read them, converted by [converter]; [logOf] gives the log of a V10 context, and [source] the rest of an instance
 * read in part.
 *
 * **Idempotence.** Every append can meet a stream an earlier run imported; the log's unique keys tell it
 * (step 3), and nothing is written twice:
 * - the request is already committed for the same instance and version: the stream is there, skipped;
 * - the request is committed for another instance of the type: 9.x keys requests by instance, V10 by tenant within the
 *   type, so the stream is imported under the request id `<requestId>#<9.x stream id>`, which is the
 *   same on every run;
 * - the version is taken: the log's stream at its version must be the 9.x stream there, by command id, and the
 *   instance resumes after it; a different stream means V10 has been written beyond 9.x, and the import stops;
 * - the log is behind (the tail met a stream whose predecessors it has not read yet): the missing streams are read
 *   from 9.x first.
 */
internal class InstanceAppends(
    private val source: NineXEventSource,
    private val logOf: (String) -> EventLog,
    private val converter: StreamConverter,
    private val options: ImportOptions,
) {
    /** The types whose walk this import claimed, or found claimed, once their first streams were in. */
    private val importing = ConcurrentHashMap.newKeySet<AggregateType>()

    /**
     * [instances] in groups of whole instances, about [ImportOptions.concurrency] of them, each of at most
     * [MAX_GROUP_STREAMS] streams but for an instance that holds more alone.
     */
    fun groupsOf(instances: List<NineXInstance>): List<List<NineXInstance>> {
        val streams = instances.sumOf { it.streams.size }
        val size = ((streams + options.concurrency - 1) / options.concurrency).coerceIn(1, MAX_GROUP_STREAMS)
        val groups = mutableListOf<List<NineXInstance>>()
        var group = mutableListOf<NineXInstance>()
        var held = 0
        for (instance in instances) {
            if (group.isNotEmpty() && held + instance.streams.size > size) {
                groups += group
                group = mutableListOf()
                held = 0
            }
            group += instance
            held += instance.streams.size
        }
        if (group.isNotEmpty()) {
            groups += group
        }
        return groups
    }

    /**
     * Imports [instances] as their page read them, with one call of the log for the streams read (group commit,
     * [EventLog.appendAll]): each instance's streams the log committed are imported; from its first stream it did not
     * commit, which the log answers as [EventLog.append] would, the instance goes on as [importInstance] does, one
     * stream at a time, so that stream's outcome is settled as it always was (already there, a request id another
     * instance holds, a version taken, an unknown outcome tried again); then the rest of an instance read in part.
     */
    suspend fun importGroup(plan: AggregatePlan, instances: List<NineXInstance>, stats: ImportStats) {
        val records = instances.map { instance ->
            instance.streams.mapIndexed { index, stream ->
                if (stream.version != index + 1) {
                    throw MigrationException(
                        "9.x stream ${stream.id} of ${plan.source}/${instance.id} is version ${stream.version} " +
                            "where ${index + 1} was expected: the 9.x history has a gap.",
                    )
                }
                stats.timed(stats.convert) { converter.convert(stream) }
            }
        }
        val outcomes = stats.timed(stats.append) {
            logOf(plan.target.context).appendAll(records.flatten().map { Append.of(it) })
        }
        claimImport(plan.target)
        var at = 0
        instances.forEachIndexed { index, instance ->
            val converted = records[index]
            val committed = outcomes.subList(at, at + converted.size).takeWhile { it is AppendOutcome.Committed }.size
            at += converted.size
            converted.take(committed).forEach(stats::imported)
            val next = if (committed == converted.size) {
                committed + 1
            } else {
                importBatch(plan, instance.id, committed + 1, instance.streams.drop(committed), stats)
            }
            if (!instance.complete || next != instance.streams.size + 1) {
                importInstance(plan, instance.id, next, stats)
            }
        }
    }

    /**
     * Imports [instance] as its page read it in a dry run, which appends nothing, so its batch always ends after the
     * streams read: those, then the rest from 9.x when the page read it in part. An import that appends goes by groups
     * ([importGroup]).
     */
    suspend fun importInstance(plan: AggregatePlan, instance: NineXInstance, stats: ImportStats) {
        val next = importBatch(plan, instance.id, 1, instance.streams, stats)
        if (!instance.complete) {
            importInstance(plan, instance.id, next, stats)
        }
    }

    /** Imports [id]'s streams from [fromVersion] on, and returns the last version the log then holds of it. */
    suspend fun importInstance(
        plan: AggregatePlan,
        id: NineXAggregateId,
        fromVersion: Int,
        stats: ImportStats
    ): Int {
        var next = fromVersion
        while (true) {
            val batch = stats.timed(stats.read) {
                blocking { source.streams(plan.source, id, next, options.streamBatch) }
            }
            if (batch.isEmpty()) {
                return next - 1
            }
            next = importBatch(plan, id, next, batch, stats)
        }
    }

    /**
     * Imports [batch], [id]'s streams from [fromVersion] on in version order, and returns the version its import goes
     * on from: the one after the batch, or the one the log made it resume from.
     */
    private suspend fun importBatch(
        plan: AggregatePlan,
        id: NineXAggregateId,
        fromVersion: Int,
        batch: List<NineXStream>,
        stats: ImportStats,
    ): Int {
        var next = fromVersion
        for (stream in batch) {
            if (stream.version != next) {
                throw MigrationException(
                    "9.x stream ${stream.id} of ${plan.source}/$id is version ${stream.version} where $next was " +
                        "expected: the 9.x history has a gap.",
                )
            }
            val record = stats.timed(stats.convert) { converter.convert(stream) }
            if (options.dryRun) {
                stats.imported(record)
                next++
                continue
            }
            stats.timed(stats.append) { append(plan, record, stream, stats) }?.let { return it }
            next++
        }
        return next
    }

    /**
     * Appends [record], converted from [stream]; returns null when the log now holds it (appended or already there),
     * or the version the instance's import resumes from.
     */
    @Suppress("CyclomaticComplexMethod") // One branch per append outcome, each settled where it is read.
    private suspend fun append(
        plan: AggregatePlan,
        record: EventStreamRecord,
        stream: NineXStream,
        stats: ImportStats,
    ): Int? {
        val log = logOf(record.ref.type.context)
        var attempt = record
        var unknowns = 0
        while (true) {
            when (val outcome = log.append(attempt)) {
                is AppendOutcome.Committed -> {
                    stats.imported(attempt)
                    if (attempt !== record) {
                        stats.rewritten.incrementAndGet()
                    }
                    return null
                }

                is AppendOutcome.AlreadyCommitted -> {
                    val existing = outcome.existing
                    if (existing.ref == attempt.ref && existing.version == attempt.version) {
                        stats.skipped.incrementAndGet()
                        return null
                    }
                    if (attempt !== record) {
                        throw MigrationException(
                            "9.x stream ${stream.id}: the request id ${attempt.requestId} is committed for " +
                                "${existing.ref} v${existing.version}.",
                        )
                    }
                    attempt = record.copyWith(requestId = "${record.requestId}#${stream.id}")
                }

                is AppendOutcome.Conflict -> {
                    val current = outcome.currentVersion
                    when {
                        current >= attempt.version -> return verified(plan, stream, log, attempt, current, stats)
                        current < attempt.version - 1 -> return current + 1
                        else -> unknowns = retry(unknowns, stream, IllegalStateException("conflict at $current"))
                    }
                }

                is AppendOutcome.Unknown -> unknowns = retry(unknowns, stream, outcome.cause)
                is AppendOutcome.Rejected -> throw MigrationException(
                    "9.x stream ${stream.id}: the log refuses ${attempt.ref} v${attempt.version}: " +
                        "${outcome.cause.message}",
                ).apply { initCause(outcome.cause) }
            }
        }
    }

    /**
     * The log already holds [record]'s instance up to [current]: checks that its stream at [current] is the 9.x one,
     * and returns the version after it.
     */
    private suspend fun verified(
        plan: AggregatePlan,
        stream: NineXStream,
        log: EventLog,
        record: EventStreamRecord,
        current: Int,
        stats: ImportStats,
    ): Int {
        val inLog = log.read(record.ref, current, current).first()
        val inNineX = blocking { source.streams(plan.source, stream.aggregateId, current, 1) }
            .firstOrNull { it.version == current }
        if (inNineX == null || inLog.commandId != inNineX.commandId) {
            throw MigrationException(
                "${record.ref} v$current in the V10 log is not the 9.x stream there (${inNineX?.id ?: "none"}): V10 " +
                    "has been written to since the import began, so the import stops.",
            )
        }
        stats.skipped.addAndGet((current - record.version + 1).toLong())
        return current + 1
    }

    private fun retry(unknowns: Int, stream: NineXStream, cause: Throwable): Int {
        if (unknowns >= options.maxUnknownRetries) {
            throw MigrationException("9.x stream ${stream.id}: the append's outcome stayed unknown: $cause")
        }
        return unknowns + 1
    }

    /**
     * Claims [type]'s access walk as this import's ([IMPORTING]) once its first streams are in, which carry no
     * attributes: when none is recorded, or in place of one that stamped nothing, such as the walk a node's creation of
     * the type's store records (a node on standby creates them as it starts). The nodes take the import's walk over as
     * any unfinished one, unless the snapshot step stamps the history and completes it. A walk that stamped aggregates
     * is left as it is.
     */
    private suspend fun claimImport(type: AggregateType) {
        if (!importing.add(type)) {
            return
        }
        val walks = logOf(type.context) as? AccessWalks ?: return
        if (!walks.holds(type)) {
            return
        }
        val walk = AccessWalk(IMPORTING, emptyMap())
        if (!walks.claim(type, walk, expected = null)) {
            walks.walk(type)?.takeIf { it.completed && it.walked == 0L && it.schema != IMPORTING }
                ?.let { walks.claim(type, walk, expected = it.schema) }
        }
    }
}

/** The streams of one group commit at most: as many as one PostgreSQL statement of the log takes. */
private const val MAX_GROUP_STREAMS = 1000
