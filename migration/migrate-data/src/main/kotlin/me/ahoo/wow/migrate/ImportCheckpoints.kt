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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.json.WowJson
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the import of one 9.x aggregate stands, so a stopped run resumes (design 07 §6):
 *
 * - [startedAt] is when its full import first started, by the tool's clock: the tail scans from there, less its
 *   look-back window, so nothing committed while the full import ran is missed;
 * - [after] is the last instance whose streams are all imported, in the full import's id order; [completed] is set
 *   once every instance is;
 * - [tailedTo] is the latest commit time the tail has read.
 *
 * Resuming repeats at most one page of instances, whose streams the import finds already in the log and skips.
 */
data class AggregateCheckpoint(
    val startedAt: Long,
    val after: NineXAggregateId? = null,
    val completed: Boolean = false,
    val tailedTo: Long? = null,
)

/**
 * Where the state comparison of one 9.x aggregate stands (`compare`): [after] is the last instance whose 9.x snapshot
 * was compared, in snapshot order, [completed] is set once every one is, and the counts are those of every run since
 * it started, so a resumed comparison reports, and exits on, the differences an earlier run found. [sample] is the
 * percentage of the instances it compares; another sample starts it over.
 */
data class CompareCheckpoint(
    val sample: Int = 100,
    val after: NineXAggregateId? = null,
    val completed: Boolean = false,
    val snapshots: Long = 0,
    val compared: Long = 0,
    val differing: Long = 0,
    val kinds: Map<String, Long> = emptyMap(),
)

/**
 * Where the snapshot writing of one 9.x aggregate stands (`snapshot`, `import --snapshots`): [after] is the last
 * instance whose V10 snapshot is written, in the import's id order, and [completed] is set once every one is.
 */
data class SnapshotCheckpoint(
    val after: NineXAggregateId? = null,
    val completed: Boolean = false,
    val written: Long = 0,
    val skipped: Long = 0,
)

/**
 * Where the reservation import of one 9.x `PrepareKey` stands ([ReservationImporter]): [after] is the last key
 * imported or skipped, in key order, [completed] is set once every key is, and the counts are those of every run
 * since it started; [released] counts the imported keys released before the walk started, 9.x no longer confirming
 * them to their owner (finding R2.4-2).
 */
data class ReservationCheckpoint(
    val after: String? = null,
    val completed: Boolean = false,
    val imported: Long = 0,
    val already: Long = 0,
    val pending: Long = 0,
    val expired: Long = 0,
    val unresolved: Long = 0,
    val released: Long = 0,
)

/**
 * Where the import of the 9.x compensation service's unresolved executions stands ([CompensationImporter]): [after]
 * is the last execution imported or reported, in id order, [completed] is set once every one is, and the counts are
 * those of every run since it started; [resolved] counts the imported entries resolved before the walk started,
 * their executions no longer unresolved in 9.x (finding R2.4-1).
 */
data class CompensationCheckpoint(
    val after: String? = null,
    val completed: Boolean = false,
    val imported: Long = 0,
    val already: Long = 0,
    val unresolved: Long = 0,
    val others: Long = 0,
    val resolved: Long = 0,
)

/**
 * The checkpoints of a migration, by 9.x aggregate (`contextName.aggregateName`), in format [format]: the import's
 * ([aggregates]), the state comparison's ([compared]) and the snapshot writing's ([snapshots]); and the reservation
 * import's by 9.x `PrepareKey` name ([reservations]); and the compensation import's ([compensation]); and the tags
 * backfill's by V10 aggregate type ([tags], decision D259). The last five joined format 1 as optional properties
 * (roadmap 8b.2, 8b.4, finding A4-8, R4-Q2c): a file without them reads as before.
 */
data class ImportCheckpoints(
    val format: Int = FORMAT,
    val aggregates: Map<String, AggregateCheckpoint> = emptyMap(),
    val compared: Map<String, CompareCheckpoint> = emptyMap(),
    val snapshots: Map<String, SnapshotCheckpoint> = emptyMap(),
    val reservations: Map<String, ReservationCheckpoint> = emptyMap(),
    val compensation: CompensationCheckpoint? = null,
    val tags: Map<String, TagsCheckpoint> = emptyMap(),
) {
    init {
        require(format == FORMAT) { "Checkpoint format $format is not supported; this tool reads format $FORMAT." }
    }

    fun of(aggregate: NineXAggregate): AggregateCheckpoint? = aggregates[aggregate.toString()]

    fun with(aggregate: NineXAggregate, checkpoint: AggregateCheckpoint): ImportCheckpoints =
        copy(aggregates = aggregates + (aggregate.toString() to checkpoint))

    fun comparisonOf(aggregate: NineXAggregate): CompareCheckpoint? = compared[aggregate.toString()]

    fun with(aggregate: NineXAggregate, checkpoint: CompareCheckpoint): ImportCheckpoints =
        copy(compared = compared + (aggregate.toString() to checkpoint))

    fun snapshotsOf(aggregate: NineXAggregate): SnapshotCheckpoint? = snapshots[aggregate.toString()]

    fun with(aggregate: NineXAggregate, checkpoint: SnapshotCheckpoint): ImportCheckpoints =
        copy(snapshots = snapshots + (aggregate.toString() to checkpoint))

    fun reservationsOf(name: String): ReservationCheckpoint? = reservations[name]

    fun with(name: String, checkpoint: ReservationCheckpoint): ImportCheckpoints =
        copy(reservations = reservations + (name to checkpoint))

    fun with(checkpoint: CompensationCheckpoint): ImportCheckpoints = copy(compensation = checkpoint)

    fun tagsOf(type: AggregateType): TagsCheckpoint? = tags[type.toString()]

    fun with(type: AggregateType, checkpoint: TagsCheckpoint): ImportCheckpoints =
        copy(tags = tags + (type.toString() to checkpoint))

    companion object {
        const val FORMAT: Int = 1
    }
}

/** Keeps a migration's [ImportCheckpoints]. */
interface CheckpointRepository {
    fun load(): ImportCheckpoints

    fun save(checkpoints: ImportCheckpoints)

    /**
     * Keeps [change] of the checkpoints kept, as one step against every other writer of them (finding R2.4-4): the
     * tail, a comparison and a snapshot writing may run at once, each changing its own entries, and none loses
     * another's. Returns the checkpoints kept.
     */
    fun update(change: (ImportCheckpoints) -> ImportCheckpoints): ImportCheckpoints {
        val changed = change(load())
        save(changed)
        return changed
    }
}

/** In memory: a dry run's, and the tests'. */
class InMemoryCheckpointRepository(private var checkpoints: ImportCheckpoints = ImportCheckpoints()) :
    CheckpointRepository {
    private val lock = Any()

    override fun load(): ImportCheckpoints = synchronized(lock) { checkpoints }

    override fun save(checkpoints: ImportCheckpoints) {
        synchronized(lock) { this.checkpoints = checkpoints }
    }

    override fun update(change: (ImportCheckpoints) -> ImportCheckpoints): ImportCheckpoints =
        synchronized(lock) { change(checkpoints).also { checkpoints = it } }
}

/**
 * A JSON file at [path], replaced atomically on each save, so a run stopped mid-write leaves the last one whole
 * (finding R2.4-4):
 *
 * - each save and [update] holds an exclusive lock on the sibling file `<file>.lock`, which other processes of the
 *   tool respect, and threads of one process wait for each other's, so that an [update] reads and writes the file as
 *   one step and two runs at once, the tail beside a comparison or a snapshot writing, never lose each other's;
 * - each writes a temporary file of its own beside it (`<file>.<random>.tmp`) and forces it to the disk before it
 *   moves it over the file, then forces the directory where the platform allows it, so that a crash leaves the old
 *   checkpoints or the new ones, not an empty file.
 *
 * The lock file stays: it is empty and the next run takes it again.
 */
class FileCheckpointRepository(path: Path) : CheckpointRepository {
    private val file: Path = path.toAbsolutePath().normalize()
    private val lockFile: Path = file.resolveSibling("${file.fileName}.lock")

    override fun load(): ImportCheckpoints = if (Files.exists(file)) {
        WowJson.mapper.readValue(file.toFile(), ImportCheckpoints::class.java)
    } else {
        ImportCheckpoints()
    }

    override fun save(checkpoints: ImportCheckpoints) {
        locked { write(checkpoints) }
    }

    override fun update(change: (ImportCheckpoints) -> ImportCheckpoints): ImportCheckpoints =
        locked { change(load()).also { write(it) } }

    /** Runs [action] holding this file's lock: the process's threads one at a time, then the file lock. */
    private fun <T> locked(action: () -> T): T = synchronized(monitors.computeIfAbsent(lockFile) { Any() }) {
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { action() }
        }
    }

    private fun write(checkpoints: ImportCheckpoints) {
        val bytes = WowJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(checkpoints)
        val temporary = Files.createTempFile(file.parent, "${file.fileName}.", ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    channel.write(buffer)
                }
                channel.force(true)
            }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
        forceDirectory()
    }

    /** Forces the directory's entries, the move included, to the disk; not every platform opens a directory. */
    private fun forceDirectory() {
        try {
            FileChannel.open(file.parent, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // Windows does not open a directory as a channel; the move is as durable as the platform makes it.
        }
    }

    private companion object {
        /** One monitor per lock file: a JVM holds a file lock once, so its threads take turns before it. */
        val monitors = ConcurrentHashMap<Path, Any>()
    }
}
