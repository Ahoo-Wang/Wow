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

/** The checkpoints of a migration, by 9.x aggregate (`contextName.aggregateName`), in format [format]. */
data class ImportCheckpoints(
    val format: Int = FORMAT,
    val aggregates: Map<String, AggregateCheckpoint> = emptyMap(),
) {
    init {
        require(format == FORMAT) { "Checkpoint format $format is not supported; this tool reads format $FORMAT." }
    }

    fun of(aggregate: NineXAggregate): AggregateCheckpoint? = aggregates[aggregate.toString()]

    fun with(aggregate: NineXAggregate, checkpoint: AggregateCheckpoint): ImportCheckpoints =
        copy(aggregates = aggregates + (aggregate.toString() to checkpoint))

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
    override fun load(): ImportCheckpoints =
        if (Files.exists(path)) WowJson.mapper.readValue(path.toFile(), ImportCheckpoints::class.java) else ImportCheckpoints()

    override fun save(checkpoints: ImportCheckpoints) {
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, WowJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(checkpoints))
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
