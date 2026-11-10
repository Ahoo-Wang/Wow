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

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.spi.log.EventRecord
import tools.jackson.core.JacksonException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * One 9.x event the V10 model does not read: its 9.x [type], in [stream], as the V10 [event] it is imported as, and
 * the [reason] the model gives. A stream that does not convert has no [type] or [event].
 */
internal data class DecodeFailure(
    val type: NineXEventType?,
    val stream: String,
    val event: String?,
    val reason: String,
) {
    fun render(): String = if (type == null) {
        "PROBLEM decode: $stream does not convert: $reason"
    } else {
        "PROBLEM decode: the 9.x event $type of $stream, read as $event: $reason"
    }
}

/**
 * The decoding of the events of one 9.x [aggregate] ([DecodeCheck]): the [streams] and [events] it read, and how many
 * events of each 9.x type [failed], with the [unconverted] streams.
 */
internal data class DecodeReport(
    val aggregate: NineXAggregate,
    val streams: Long,
    val events: Long,
    val failed: Map<NineXEventType, Long>,
    val unconverted: Long,
) {
    /** Whether every event read. */
    val passed: Boolean get() = failed.isEmpty() && unconverted == 0L

    fun render(): String = buildString {
        append("$aggregate decoded: $streams streams, $events events")
        if (passed) {
            append(", every one read")
        } else {
            append(", ${failed.values.sum()} events do not read")
            failed.entries.sortedBy { it.key.toString() }.forEach { (type, count) -> append("; $type: $count") }
            if (unconverted > 0) {
                append("; $unconverted streams do not convert")
            }
        }
    }
}

/**
 * Reads every 9.x event of an aggregate the map imports as its V10 model reads it (`check --decode`): each stream
 * converted as the import converts it ([StreamConverter.eventsOf]), then each of its V10 records decoded by the
 * aggregate's model, through its upgrades, as the service decodes it when it loads the aggregate
 * ([EventCodec.decodeUnlessRetired]). It writes nothing.
 *
 * Every event that does not read is [report]ed as it is found, by its 9.x type, its stream and the model's reason, as
 * is a stream that does not convert, or the store holds without a field the import needs,
 * and the walk goes on, so one pass lists all of them: the models' Kotlin module refuses some shapes 9.x events
 * stored, such as a null element of a list of non-null strings, and each type that fails needs an `@Upgrade` that
 * cleans its old shape.
 * Instances are walked as the import walks them, [ImportOptions.concurrency] at a time; an instance the map excludes
 * ([MigrationPlan.excludes]) is passed over.
 */
internal class DecodeCheck(
    private val source: NineXEventSource,
    private val plan: MigrationPlan,
    private val models: MigrationModels,
    private val options: ImportOptions = ImportOptions(),
    private val report: (DecodeFailure) -> Unit = {},
    private val codec: EventCodec = EventCodec(),
) {
    private val converter = StreamConverter(plan)

    /** Decodes every event of [aggregate]'s instances, reporting each that does not read. */
    suspend fun check(aggregate: NineXAggregate): DecodeReport {
        val model = models.of(plan.of(aggregate).target)
        val stats = Stats()
        val permits = Semaphore(options.concurrency)
        var after: NineXAggregateId? = null
        while (true) {
            val page = blocking {
                source.instances(aggregate, after, options.pageSize, options.pageStreams)
            }
            if (page.isEmpty()) {
                break
            }
            coroutineScope {
                page.filterNot { plan.excludes(aggregate, it.id.id) }
                    .map { async { permits.withPermit { decodeInstance(aggregate, model, it, stats) } } }
                    .awaitAll()
            }
            after = page.last().id
        }
        return DecodeReport(
            aggregate,
            stats.streams.get(),
            stats.events.get(),
            stats.failed.toMap(),
            stats.unconverted.get()
        )
    }

    /** Decodes [instance] as its page read it, then the rest of its streams when the page read it in part. */
    private suspend fun decodeInstance(
        aggregate: NineXAggregate,
        model: AggregateModel<Any>,
        instance: NineXInstance,
        stats: Stats,
    ) {
        instance.streams.forEach { decodeStream(model, it, stats) }
        if (!instance.complete) {
            decodeInstance(aggregate, model, instance.id, (instance.streams.lastOrNull()?.version ?: 0) + 1, stats)
        }
    }

    private suspend fun decodeInstance(
        aggregate: NineXAggregate,
        model: AggregateModel<Any>,
        id: NineXAggregateId,
        fromVersion: Int,
        stats: Stats,
    ) {
        var next = fromVersion
        while (true) {
            val batch = try {
                blocking { source.streams(aggregate, id, next, options.streamBatch) }
            } catch (unreadable: MigrationException) {
                // A stream the store holds without a field the import needs: the instance's later ones are not read.
                stats.unconverted.incrementAndGet()
                report(DecodeFailure(null, "the 9.x streams of $aggregate/$id from v$next", null, reasonOf(unreadable)))
                return
            }
            if (batch.isEmpty()) {
                return
            }
            batch.forEach { decodeStream(model, it, stats) }
            next = batch.last().version + 1
        }
    }

    private fun decodeStream(model: AggregateModel<Any>, stream: NineXStream, stats: Stats) {
        stats.streams.incrementAndGet()
        val events = try {
            converter.eventsOf(stream)
        } catch (unconverted: MigrationException) {
            stats.unconverted.incrementAndGet()
            report(DecodeFailure(null, converter.subjectOf(stream), null, reasonOf(unconverted)))
            return
        }
        events.forEach { (event, records) ->
            stats.events.incrementAndGet()
            val type = NineXEventType(event.name, event.revision)
            val unreadable = records.mapNotNull { record -> failureOf(model, record)?.let { record to it } }
            if (unreadable.isNotEmpty()) {
                stats.failed.merge(type, 1, Long::plus)
            }
            unreadable.forEach { (record, failure) ->
                val readAs = "${record.name} revision ${record.revision}"
                report(DecodeFailure(type, converter.subjectOf(stream), readAs, reasonOf(failure)))
            }
        }
    }

    /** What [model] throws reading [record], an upgrade that refuses it among them, as the kernel catches it; null when it reads. */
    private fun failureOf(model: AggregateModel<Any>, record: EventRecord): RuntimeException? = try {
        codec.decodeUnlessRetired(model, record)
        null
    } catch (@Suppress("TooGenericExceptionCaught") unreadable: RuntimeException) {
        unreadable
    }

    private class Stats {
        val streams = AtomicLong()
        val events = AtomicLong()
        val unconverted = AtomicLong()
        val failed = ConcurrentHashMap<NineXEventType, Long>()
    }
}

/**
 * Why [failure] happened, on one line: its message's first line, and the path into the JSON where Jackson failed,
 * when it did; the failure's class when it has no message.
 */
internal fun reasonOf(failure: Throwable): String {
    val line = failure.message?.lineSequence()?.first() ?: failure.javaClass.name
    val path = generateSequence(failure) { it.cause }.filterIsInstance<JacksonException>().firstOrNull()
        ?.pathReference?.takeIf { it.isNotEmpty() }
    return if (path == null) line else "$line (at $path)"
}
