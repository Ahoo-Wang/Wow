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

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A [CheckpointTable] in memory, every operation one step, as the target's transactions make them. */
internal class InMemoryCheckpointTable : CheckpointTable {
    private val checkpoints = HashMap<String, StoredCheckpoints>()
    private val keys = mutableListOf<Triple<String, Long, ImportedKey>>()
    private var sequence = 0L

    /** Runs before each [load], as another run might write between two steps of one. */
    var beforeLoad: () -> Unit = {}

    override fun load(name: String): StoredCheckpoints? {
        beforeLoad()
        return synchronized(this) { checkpoints[name] }
    }

    @Synchronized
    override fun write(
        name: String,
        version: Long,
        checkpoints: ImportCheckpoints,
        fences: Map<String, Long>
    ): Boolean {
        if ((this.checkpoints[name]?.version ?: 0) != version) {
            return false
        }
        this.checkpoints[name] = StoredCheckpoints(checkpoints, version + 1, fences)
        return true
    }

    @Synchronized
    override fun addKeys(name: String, keys: List<ImportedKey>, admit: (Map<String, Long>) -> Unit) {
        admit(checkpoints[name]?.fences.orEmpty())
        keys.forEach { this.keys += Triple(name, ++sequence, it) }
    }

    @Synchronized
    override fun keys(name: String, prepareKey: String, after: String?, limit: Int): List<RecordedKey> =
        keys.filter { (checkpoint, position, key) ->
            checkpoint == name && key.name == prepareKey && position > (after?.toLong() ?: 0)
        }.take(limit).map { RecordedKey(it.second.toString(), it.third) }

    @Synchronized
    override fun removeKeys(name: String, positions: List<String>, admit: (Map<String, Long>) -> Unit) {
        admit(checkpoints[name]?.fences.orEmpty())
        keys.removeAll { it.first == name && it.second.toString() in positions }
    }
}

/** A clock the test moves. */
internal class MovableClock(@Volatile var millis: Long = 1_000_000) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = Instant.ofEpochMilli(millis)

    override fun millis(): Long = millis
}
