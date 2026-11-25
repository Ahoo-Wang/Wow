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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.subscription.ImportCutover
import org.junit.jupiter.api.Test

/** Where the import records the cut-over ([CutoverRecorder]): where its own streams end, and before it moves on. */
internal class ImportCutoverTest : EventStreamImporterFixtures() {
    private val store = InMemoryCheckpointStore()
    private val recorder = CutoverRecorder(listOf(NineXExample.CONTEXT), { store })
    private val streams = NineXExample.streams().filter { it.aggregate == order }
    private val ref = NineXExample.orderModel.type.ref("(0)", streams.first().aggregateId.id)

    @Test
    fun `the cut-over ends at the import's last stream, never at a commit V10 appended after it`(): Unit = runTest {
        val source = InMemoryNineXEventSource(streams)
        now = streams.last().createTime + 1
        importer(source, cutover = recorder).importAll(order)
        recorder.record().assert().isEqualTo(mapOf(NineXExample.CONTEXT to 1))
        val imported = heads()
        cutover().assert().isEqualTo(imported)
        val last = log.read(ref).toList().last()
        log.append(last.copyWith(version = last.version + 1, commandId = "v10-command", requestId = "v10-request"))

        // A walk again from the first, and a tail over the whole history, find every 9.x stream already in the log.
        val again = CutoverRecorder(listOf(NineXExample.CONTEXT), { store })
        importer(source, ImportOptions(restart = true), cutover = again).importAll(order)
        importer(source, ImportOptions(lookbackMillis = now), cutover = again).tailOnce(order)
        again.record()

        heads().assert().describedAs("V10 appended").isNotEqualTo(imported)
        cutover().assert().isEqualTo(imported)
    }

    @Test
    fun `a run records where the log holds what an earlier run appended and did not record`(): Unit = runTest {
        val source = InMemoryNineXEventSource(streams)
        now = streams.last().createTime + 1
        // The earlier run stopped after appending, before it recorded anything.
        importer(source).importAll(order)

        importer(source, ImportOptions(restart = true), cutover = recorder).importAll(order).also {
            it.skipped.assert().isEqualTo(streams.size.toLong())
        }
        recorder.record()
        cutover().assert().isEqualTo(heads())
    }

    @Test
    fun `the tail records where the log holds the latest stream it skips`(): Unit = runTest {
        val source = InMemoryNineXEventSource(streams)
        now = streams.last().createTime + 1
        importer(source).importAll(order)

        importer(source, ImportOptions(lookbackMillis = now), cutover = recorder).tailOnce(order).also {
            it.skipped.assert().isEqualTo(streams.size.toLong())
        }
        cutover().assert().describedAs("recorded before the tail's checkpoint moved").isEqualTo(heads())
    }

    @Test
    fun `an instance the log holds under other request ids is recorded where its import resumes after`(): Unit =
        runTest {
            val held = streams.take(2)
            val converter = StreamConverter(NineXExample.plan)
            // Only the version tells they are there, as PostgreSQL reports it: the import verifies them.
            held.forEach { log.append(converter.convert(it).copyWith(requestId = "r${it.version}")) }
            importer(InMemoryNineXEventSource(held), cutover = recorder).importAll(order).also {
                it.imported.assert().isEqualTo(0)
                it.skipped.assert().isEqualTo(2)
            }
            recorder.record()
            cutover().assert().isEqualTo(heads())
        }

    @Test
    fun `the import records a page's streams before its checkpoint moves past them`(): Unit = runTest {
        val first = NineXExample.stream("order-stream-v1.json")
        val source = InMemoryNineXEventSource((1..5).map { copyOf(first, "order-$it") })
        // At each move of the checkpoint: the instance it moves after, and the cut-over then.
        val moves = mutableListOf<Pair<String, Map<Int, Long>>>()
        val watched = object : CheckpointRepository by checkpoints {
            override fun update(change: (ImportCheckpoints) -> ImportCheckpoints): ImportCheckpoints =
                checkpoints.update(change).also { kept ->
                    kept.of(order)?.ranges?.single()?.after?.let { moves += it.id to runBlocking { cutover() } }
                }
        }
        val options = ImportOptions(pageSize = 1, concurrency = 1, readParallelism = 1)
        EventStreamImporter(source, NineXExample.plan, { log }, watched, options, cutover = recorder).importAll(order)

        moves.map { it.first }.distinct().assert()
            .containsExactly("order-1", "order-2", "order-3", "order-4", "order-5")
        moves.forEach { (id, cutover) ->
            val at = log.positionOf(NineXExample.orderModel.type.ref("(0)", id), 1)!!
            cutover[at.partition].assert().describedAs("the cut-over when the checkpoint moved after $id")
                .isNotNull().isGreaterThanOrEqualTo(at.sequence)
        }
    }

    @Test
    fun `a run that imports nothing says the cut-over stays`(): Unit = runTest {
        val recorded = recorder.record()
        recorded.assert().isEqualTo(mapOf(NineXExample.CONTEXT to 0))
        recorder.render(recorded).assert()
            .isEqualTo("CUTOVER example: this run imported nothing into the log, so the cut-over stays where it was.")
        cutover().assert().isEmpty()
    }

    /** The cut-over recorded in [store], by partition. */
    private suspend fun cutover(): Map<Int, Long> =
        store.list(ImportCutover.groupOf(NineXExample.CONTEXT)).associate { it.partition to it.position }

    /** The log's head of each partition that holds an entry. */
    private suspend fun heads(): Map<Int, Long> =
        (0 until log.partitions).mapNotNull { partition -> log.head(partition)?.let { partition to it } }.toMap()
}
