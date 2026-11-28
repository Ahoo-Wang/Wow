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

package me.ahoo.wow.runtime.source

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.transport.PartitionOffsets
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.SourceMessage

/**
 * The incarnations of one topic its subscriptions read ([PartitionedSource.incarnation]): a topic deleted and created
 * again under its name is another incarnation, whose offsets name other messages, so its subscriptions keep their
 * checkpoints per incarnation, in the group [groupOf] names, and never apply an offset of one to another.
 *
 * Each incarnation a node joins is recorded in the topic's lease group, [base], as the checkpoint of a marker
 * subscription ([MARKER] and the incarnation), so a node that starts after the topic was created again finds the
 * incarnations before it ([earlier]) and reports their checkpoints, which are kept and no longer read.
 */
internal class TopicIncarnations(
    private val topic: String,
    private val base: String,
    private val checkpoints: CheckpointStore,
) {
    /** The checkpoint group of [incarnation]: [base] itself for a source that cannot tell its incarnations. */
    fun groupOf(incarnation: String?): String = incarnation?.let { "$base$SEPARATOR$it" } ?: base

    /** Records [incarnation] as one the topic's subscriptions have read; nothing for a source that tells none. */
    suspend fun record(incarnation: String?) {
        incarnation ?: return
        checkpoints.save(base, MARKER + incarnation, 0, 0L)
    }

    /**
     * The checkpoints left under incarnations other than [current], by incarnation: each one recorded ([record]) and
     * the checkpoints of a source that told none (under [base], the empty name), those of a pre-release among them.
     * Empty for a source that tells no incarnation, whose checkpoints are [base]'s own.
     */
    suspend fun earlier(current: String?): Map<String, List<Checkpoint>> {
        current ?: return emptyMap()
        val recorded = checkpoints.list(base)
        val (markers, unnamed) = recorded.partition { it.subscription.startsWith(MARKER) }
        val others = markers.map { it.subscription.removePrefix(MARKER) }.filter { it != current }.distinct().sorted()
        return buildMap {
            if (unnamed.isNotEmpty()) {
                put("", unnamed)
            }
            others.forEach { incarnation ->
                checkpoints.list(groupOf(incarnation)).takeIf { it.isNotEmpty() }?.let { put(incarnation, it) }
            }
        }
    }

    /**
     * [source] as the subscriptions of [incarnation] read it: each read it places, each read of a partition's offsets
     * and each read at offsets first asks the source which incarnation the topic is now, so none is placed in another
     * incarnation; a read at offsets asks again once it has read, before it answers. Another incarnation is refused by
     * name ([TopicIncarnationException]) until the subscriptions join it. [source] itself for a source that tells no
     * incarnation: nothing is asked then.
     *
     * A read placed hands the source's messages on as they are, asked nothing more: nothing is added per message. A
     * topic deleted and created again after the read's check is met as the source meets it. One of Kafka goes on
     * fetching its offset under the topic's name, and ends on an offset out of range once the new partition is shorter
     * than it, as a topic just created is; only one whose first fetch after the recreation finds the new partition
     * already at its offset reads on in it, until the refresh round joins the new incarnation.
     */
    fun guarded(source: PartitionedSource, incarnation: String?): PartitionedSource =
        if (incarnation == null) source else Guarded(source, topic, incarnation)

    private class Guarded(
        private val source: PartitionedSource,
        private val topic: String,
        private val incarnation: String,
    ) : PartitionedSource by source {
        private suspend fun check(topic: String) {
            if (topic != this.topic) return
            val current = source.incarnation(topic)
            if (current != incarnation) {
                throw TopicIncarnationException(topic, incarnation, current)
            }
        }

        /** [read] once [topic] is checked, its messages handed on as the source's flow emits them. */
        private fun guarded(topic: String, read: () -> Flow<SourceMessage>): Flow<SourceMessage> = flow {
            check(topic)
            emitAll(read())
        }

        override fun read(topic: String, partition: Int, from: Long): Flow<SourceMessage> =
            guarded(topic) { source.read(topic, partition, from) }

        override fun read(topic: String, partition: Int, from: Long, until: Long): Flow<SourceMessage> =
            guarded(topic) { source.read(topic, partition, from, until) }

        override suspend fun readAt(
            topic: String,
            partition: Int,
            offsets: Collection<Long>,
        ): Map<Long, SourceMessage> {
            check(topic)
            return source.readAt(topic, partition, offsets).also { if (it.isNotEmpty()) check(topic) }
        }

        override suspend fun offsets(topic: String, partition: Int): PartitionOffsets {
            check(topic)
            return source.offsets(topic, partition)
        }
    }

    companion object {
        /** Between the lease group's name and the incarnation in a checkpoint group's. */
        const val SEPARATOR = "#"

        /** The marker subscription of an incarnation recorded in the lease group ([record]). */
        const val MARKER = "wow.incarnation#"
    }
}

/**
 * A read of [topic] placed by the subscriptions of its incarnation [joined], when the topic is [current] now (null:
 * it does not exist now). The read places nothing: the subscriptions join the new incarnation when they next read the
 * topic's partitions, and a node that starts joins the one it finds.
 */
internal class TopicIncarnationException(val topic: String, val joined: String, val current: String?) :
    IllegalStateException(
        if (current == null) {
            "Topic $topic does not exist now; its subscriptions read its incarnation $joined, and wait for it."
        } else {
            "Topic $topic was created again: it is incarnation $current now, and its subscriptions' offsets are of " +
                "$joined. No read is placed until they join $current, at the next refresh of the topic's partitions."
        },
    )
