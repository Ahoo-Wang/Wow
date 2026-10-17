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

import me.ahoo.wow.spi.json.WowJson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
 * since it started.
 */
data class ReservationCheckpoint(
    val after: String? = null,
    val completed: Boolean = false,
    val imported: Long = 0,
    val already: Long = 0,
    val pending: Long = 0,
    val expired: Long = 0,
    val unresolved: Long = 0,
)

/**
 * Where the import of the 9.x compensation service's unresolved executions stands ([CompensationImporter]): [after]
 * is the last execution imported or reported, in id order, [completed] is set once every one is, and the counts are
 * those of every run since it started.
 */
data class CompensationCheckpoint(
    val after: String? = null,
    val completed: Boolean = false,
    val imported: Long = 0,
    val already: Long = 0,
    val unresolved: Long = 0,
    val others: Long = 0,
)

/**
 * The checkpoints of a migration, by 9.x aggregate (`contextName.aggregateName`), in format [format]: the import's
 * ([aggregates]), the state comparison's ([compared]) and the snapshot writing's ([snapshots]); and the reservation
 * import's by 9.x `PrepareKey` name ([reservations]); and the compensation import's ([compensation]). The last four
 * joined format 1 as optional properties (roadmap 8b.2, 8b.4, finding A4-8): a file without them reads as before.
 */
data class ImportCheckpoints(
    val format: Int = FORMAT,
    val aggregates: Map<String, AggregateCheckpoint> = emptyMap(),
    val compared: Map<String, CompareCheckpoint> = emptyMap(),
    val snapshots: Map<String, SnapshotCheckpoint> = emptyMap(),
    val reservations: Map<String, ReservationCheckpoint> = emptyMap(),
    val compensation: CompensationCheckpoint? = null,
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

    companion object {
        const val FORMAT: Int = 1
    }
}

/** Keeps a migration's [ImportCheckpoints]. */
interface CheckpointRepository {
    fun load(): ImportCheckpoints

    fun save(checkpoints: ImportCheckpoints)
}

/** In memory: a dry run's, and the tests'. */
class InMemoryCheckpointRepository(private var checkpoints: ImportCheckpoints = ImportCheckpoints()) :
    CheckpointRepository {
    override fun load(): ImportCheckpoints = checkpoints

    override fun save(checkpoints: ImportCheckpoints) {
        this.checkpoints = checkpoints
    }
}

/** A JSON file at [path], replaced atomically on each save, so a run stopped mid-write leaves the last one whole. */
class FileCheckpointRepository(private val path: Path) : CheckpointRepository {
    override fun load(): ImportCheckpoints = if (Files.exists(path)) {
        WowJson.mapper.readValue(path.toFile(), ImportCheckpoints::class.java)
    } else {
        ImportCheckpoints()
    }

    override fun save(checkpoints: ImportCheckpoints) {
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, WowJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(checkpoints))
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
