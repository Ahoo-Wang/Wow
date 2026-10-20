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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.TagsBackfill
import me.ahoo.wow.spi.state.StateEventRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The tags backfill (decision D259): each aggregate replayed, the tags after each stream recorded where none were. */
class TagsBackfillerTest {
    class Parcel(var region: String = "")

    class Moved(val region: String)

    private val model = aggregate<Parcel>("shipping", "parcel") {
        initial { Parcel() }
        evolve<Moved> { region = it.region }
        tags { mapOf("region" to listOfNotNull(it.region.takeIf(String::isNotEmpty))) }
    }
    private val type: AggregateType = model.type
    private val codec = EventCodec()
    private val log = BackfilledLog(InMemoryEventLog(2))
    private val checkpoints = InMemoryCheckpointRepository()
    private val reports = mutableListOf<TagsProgress>()

    private fun stream(id: String, version: Int, region: String, tags: Map<String, List<String>>? = null) =
        EventStreamRecord(
            ref = type.ref("tenant", id),
            version = version,
            commandId = "$id-$version",
            requestId = "$id-$version",
            commandType = "shipping.parcel.Move",
            commitTime = version.toLong(),
            events = listOf(codec.encode(model, Moved(region))),
            scope = Scope.NONE,
            tags = tags,
        )

    private fun backfiller(options: ImportOptions = ImportOptions()) = TagsBackfiller(
        MigrationModels(listOf(model)),
        { log },
        checkpoints,
        options,
        { reports += it },
    )

    private suspend fun tagsOf(id: String) = log.read(type.ref("tenant", id)).toList().map { it.tags }

    @Test
    fun `each stream without tags gets the tags its aggregate had after it, recorded ones are kept`() = runTest {
        listOf(
            stream("p-1", 1, "east"),
            stream("p-1", 2, "west"),
            stream("p-2", 1, "north", tags = mapOf("region" to listOf("north"))),
            stream("p-2", 2, "south"),
            stream("p-3", 1, "east", tags = mapOf("region" to listOf("east"))),
        ).forEach { log.append(it) }
        val progress = backfiller(ImportOptions(pageSize = 1)).backfill(type)
        tagsOf("p-1").assert().containsExactly(mapOf("region" to listOf("east")), mapOf("region" to listOf("west")))
        tagsOf("p-2").assert().containsExactly(mapOf("region" to listOf("north")), mapOf("region" to listOf("south")))
        tagsOf("p-3").assert().containsExactly(mapOf("region" to listOf("east")))
        progress.checkpoint.completed.assert().isTrue()
        progress.checkpoint.aggregates.assert().isEqualTo(2)
        progress.checkpoint.streams.assert().isEqualTo(3)
        progress.render().assert().startsWith("shipping.parcel tags done: 2 aggregates replayed, 3 streams tagged")
        reports.first().checkpoint.completed.assert().isFalse()
        backfiller().untagged(type).assert().isFalse()
        // Done: run again, nothing is walked; a restart walks from the first and finds nothing left.
        backfiller().backfill(type).checkpoint.aggregates.assert().isEqualTo(2)
        backfiller(ImportOptions(restart = true)).backfill(type).checkpoint.aggregates.assert().isEqualTo(0)
    }

    @Test
    fun `an aggregate that skips a version stops the backfill, and a log without tags refuses it`() = runTest {
        log.append(stream("p-1", 1, "east"))
        log.append(stream("p-1", 2, "west"))
        log.gap = true
        assertThrows<MigrationException> { backfiller().backfill(type) }.message!!.assert().contains("skips a version")
        backfiller().untagged(type).assert().isTrue()
        val untaggable = object : EventLog by InMemoryEventLog(2) {}
        val plain = TagsBackfiller(MigrationModels(listOf(model)), { untaggable }, checkpoints)
        assertThrows<MigrationException> { plain.backfill(type) }.message!!.assert().contains("records no tags")
        plain.untagged(type).assert().isFalse()
    }

    /** Documents in memory, each listed while it records no tags; the overwritten ones recorded. */
    private class Documents(streams: List<IndexedStream>) : UntaggedDocuments {
        val untagged = streams.sortedWith(compareBy({ it.ref.id }, { it.ref.tenantId }, { it.version })).toMutableList()
        val overwritten = mutableListOf<StateEventRecord>()
        var pages = 0

        override suspend fun untagged(after: IndexedStream?, limit: Int): List<IndexedStream> {
            pages++
            val start = after?.let { untagged.indexOfFirst { stream -> stream == it } + 1 } ?: 0
            return untagged.drop(start).take(limit)
        }

        override suspend fun overwrite(records: List<StateEventRecord>) {
            overwritten += records
            records.forEach { record -> untagged.removeIf { it.ref == record.ref && it.version == record.version } }
        }
    }

    private fun indexed(id: String, version: Int) = IndexedStream(type.ref("tenant", id), version)

    @Test
    fun `the re-sink overwrites the untagged documents from the backfilled log, page by page, then nothing`() =
        runTest {
            listOf(stream("p-1", 1, "east"), stream("p-1", 2, "west"), stream("p-2", 1, "north")).forEach {
                log.append(it)
            }
            val documents = Documents(listOf(indexed("p-1", 1), indexed("p-1", 2), indexed("p-2", 1)))
            assertThrows<MigrationException> { backfiller().resink(type, documents) }.message!!.assert()
                .contains("backfill it first", "--force")
            documents.overwritten.assert().isEmpty()
            backfiller().backfill(type)
            backfiller(ImportOptions(pageSize = 2)).resink(type, documents).assert().isEqualTo(3)
            documents.overwritten.map { it.ref.id to it.version }.assert()
                .containsExactly("p-1" to 1, "p-1" to 2, "p-2" to 1)
            documents.overwritten.map { it.tags }.assert().containsExactly(
                mapOf("region" to listOf("east")),
                mapOf("region" to listOf("west")),
                mapOf("region" to listOf("north")),
            )
            documents.overwritten.first().events.single().name.assert().isEqualTo("shipping.parcel.Moved")
            documents.untagged.assert().isEmpty()
            backfiller().resink(type, documents).assert().isEqualTo(0)
        }

    @Test
    fun `forced, the re-sink leaves the documents of streams still without tags, and one missing from the log fails`() =
        runTest {
            log.append(stream("p-1", 1, "east"))
            backfiller().backfill(type)
            log.append(stream("p-2", 1, "west"))
            val documents = Documents(listOf(indexed("p-1", 1), indexed("p-2", 1)))
            backfiller().resink(type, documents, force = true).assert().isEqualTo(1)
            documents.untagged.map { it.ref.id }.assert().containsExactly("p-2")
            val ghost = Documents(listOf(indexed("p-9", 1)))
            assertThrows<MigrationException> { backfiller().resink(type, ghost, force = true) }.message!!.assert()
                .contains("is indexed but not in the V10 log")
        }

    /** A log in memory that backfills tags, in its key order; [gap] hides every first stream. */
    class BackfilledLog(private val log: InMemoryEventLog) :
        EventLog by log,
        TagsBackfill {
        private val refs = sortedSetOf(compareBy<AggregateRef>({ it.id }, { it.tenantId }))
        private val recorded = HashMap<Pair<AggregateRef, Int>, Map<String, List<String>>>()
        var gap = false

        override suspend fun append(stream: EventStreamRecord, transport: me.ahoo.wow.spi.log.Transport?) =
            log.append(stream, transport).also { refs += stream.ref }

        override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> =
            log.read(ref, if (gap) 2 else fromVersion, toVersion).map { stream ->
                recorded[ref to stream.version]?.let { stream.copyWith(tags = it) } ?: stream
            }

        override suspend fun untagged(type: AggregateType, after: AggregateRef?, limit: Int): List<AggregateRef> =
            refs.filter { ref -> after == null || refs.comparator().compare(ref, after) > 0 }
                .filter { ref -> log.read(ref).toList().any { it.tags == null && (ref to it.version) !in recorded } }
                .take(limit)

        override suspend fun recordTags(ref: AggregateRef, tagsByVersion: Map<Int, Map<String, List<String>>>): Int {
            val untagged = log.read(ref).toList().filter { it.tags == null }.map { it.version }.toSet()
            return tagsByVersion.filterKeys { it in untagged && (ref to it) !in recorded }
                .onEach { (version, tags) -> recorded[ref to version] = tags }.size
        }
    }
}
