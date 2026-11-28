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

package me.ahoo.wow.runtime.command

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ahoo.wow.runtime.source.TopicIncarnations
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.transport.PartitionedSource

/**
 * The incarnation of a context's command [topic] its intake reads ([PartitionedSource.incarnation]): a topic deleted
 * and created again under its name holds other commands at the same offsets, so the intake keeps its checkpoints per
 * incarnation, as a topic subscription does ([TopicIncarnations]).
 *
 * The intake's runtime is built with its context and runs in its log group's ownership ([group], whose name it keeps
 * for its parked entries), so the incarnation is resolved once, at its first read of its [checkpoints] as it starts,
 * and kept until the node stops: a command topic created again while the node runs is refused by name
 * ([source], [TopicIncarnations.guarded]) until the node starts again, and is then read from where the intake starts,
 * its beginning. The incarnation is recorded under [group]'s name ([TopicIncarnations.record]), whose orphan report
 * passes over Wow's own names, and the checkpoints of the incarnations before it are told once, at that start.
 */
internal class IntakeIncarnation(
    private val source: PartitionedSource,
    private val topic: String,
    private val group: String,
    private val store: CheckpointStore,
) {
    private val incarnations = TopicIncarnations(topic, group, store)
    private val lock = Mutex()

    @Volatile
    private var resolved = false

    /** The incarnation the intake reads, once resolved ([resolve]); null before, and for a source that tells none. */
    @Volatile
    var incarnation: String? = null
        private set

    /** The incarnation the intake reads, asked of the source the first time and kept from then on. */
    suspend fun resolve(): String? {
        if (resolved) return incarnation
        lock.withLock {
            if (!resolved) {
                val current = source.incarnation(topic)
                incarnations.record(current)
                incarnations.earlierReport(current, left = null)?.let { report -> logger.warn { report } }
                incarnation = current
                resolved = true
            }
        }
        return incarnation
    }

    /**
     * [source] as the intake reads it: each read it places is checked against the incarnation resolved, and one of
     * another incarnation is refused, its refusal saying the intake joins it when its node starts again ([JOINS]).
     */
    val guarded: PartitionedSource = incarnations.guarded(source, JOINS) { resolve() }

    /** The intake's entries of [topic], of a log of [partitions] partitions: read [guarded], parked with [incarnation]. */
    fun entries(partitions: Int, observer: Observer): MessageEntries =
        MessageEntries.commands(topic, guarded, partitions, observer) { incarnation }

    /** The intake's checkpoints: those of [group] are kept under the group of the incarnation it reads. */
    val checkpoints: CheckpointStore = object : CheckpointStore {
        private suspend fun of(group: String): String =
            if (group == this@IntakeIncarnation.group) incarnations.groupOf(resolve()) else group

        override suspend fun load(group: String, subscription: String, partition: Int): Long? =
            store.load(of(group), subscription, partition)

        override suspend fun list(group: String): List<Checkpoint> = store.list(of(group))

        override suspend fun list(group: String, subscription: String): List<Checkpoint> =
            store.list(of(group), subscription)

        override suspend fun save(group: String, subscription: String, partition: Int, position: Long) =
            store.save(of(group), subscription, partition, position)

        override suspend fun saveAll(group: String, checkpoints: List<Checkpoint>) =
            store.saveAll(of(group), checkpoints)
    }

    private companion object {
        val logger = KotlinLogging.logger {}

        /** When the intake joins a new incarnation of its topic, as its refusals say. */
        const val JOINS = "when its node starts again"
    }
}
