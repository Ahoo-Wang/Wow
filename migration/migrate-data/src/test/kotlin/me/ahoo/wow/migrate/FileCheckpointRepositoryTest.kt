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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Finding R2.4-4: the checkpoints file read and written as one step by every writer, threads and processes alike. */
class FileCheckpointRepositoryTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `threads updating the same checkpoints file through their own repositories should lose nothing`() {
        val path = directory.resolve("checkpoint.json")
        val start = CountDownLatch(1)
        val writers = (0 until THREADS).map { writer ->
            thread {
                val repository = FileCheckpointRepository(path)
                start.await()
                writeAll(repository, "t$writer", UPDATES)
            }
        }
        start.countDown()
        writers.forEach { it.join() }
        val kept = FileCheckpointRepository(path).load().aggregates.keys
        kept.assert().hasSize(THREADS * UPDATES)
        leftovers().assert().isEmpty()
    }

    @Test
    fun `two processes updating the same checkpoints file should lose nothing`() {
        val path = directory.resolve("checkpoint.json")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val other = ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            CheckpointWriter::class.java.name,
            path.toString(),
            "process",
            UPDATES.toString(),
        ).inheritIO().start()
        writeAll(FileCheckpointRepository(path), "test", UPDATES)
        other.waitFor(1, TimeUnit.MINUTES).assert().isTrue()
        other.exitValue().assert().isEqualTo(0)
        val kept = FileCheckpointRepository(path).load().aggregates.keys
        kept.filter { it.startsWith("process.") }.assert().hasSize(UPDATES)
        kept.filter { it.startsWith("test.") }.assert().hasSize(UPDATES)
        leftovers().assert().isEmpty()
    }

    @Test
    fun `an update should return the checkpoints kept, in memory too`() {
        val aggregate = NineXAggregate("example-service", "order")
        val checkpoint = AggregateCheckpoint(startedAt = 1)
        val memory = InMemoryCheckpointRepository()
        memory.update { it.with(aggregate, checkpoint) }.of(aggregate).assert().isEqualTo(checkpoint)
        memory.load().of(aggregate).assert().isEqualTo(checkpoint)
        val file = FileCheckpointRepository(directory.resolve("checkpoint.json"))
        file.update { it.with(aggregate, checkpoint) }.of(aggregate).assert().isEqualTo(checkpoint)
        file.load().of(aggregate).assert().isEqualTo(checkpoint)
        // The default of the interface: a repository of its own.
        val plain = object : CheckpointRepository {
            var kept = ImportCheckpoints()

            override fun load(): ImportCheckpoints = kept

            override fun save(checkpoints: ImportCheckpoints) {
                kept = checkpoints
            }
        }
        plain.update { it.with(aggregate, checkpoint) }.of(aggregate).assert().isEqualTo(checkpoint)
        plain.kept.of(aggregate).assert().isEqualTo(checkpoint)
    }

    /** The temporary files left beside the checkpoints: none once the writers are done. */
    private fun leftovers(): List<String> =
        directory.listDirectoryEntries().map { it.name }.filter { it.endsWith(".tmp") }

    private companion object {
        const val THREADS = 4
        const val UPDATES = 40
    }
}

/** Updates [repository] [count] times, each adding the aggregate `<prefix>.a<n>`. */
internal fun writeAll(repository: CheckpointRepository, prefix: String, count: Int) {
    repeat(count) { n ->
        repository.update { it.with(NineXAggregate(prefix, "a$n"), AggregateCheckpoint(startedAt = n.toLong())) }
    }
}

/** The other process of [FileCheckpointRepositoryTest]: `<path> <prefix> <count>`. */
internal object CheckpointWriter {
    @JvmStatic
    fun main(args: Array<String>) {
        check(Files.isDirectory(Path.of(args[0]).toAbsolutePath().parent))
        writeAll(FileCheckpointRepository(Path.of(args[0])), args[1], args[2].toInt())
    }
}
