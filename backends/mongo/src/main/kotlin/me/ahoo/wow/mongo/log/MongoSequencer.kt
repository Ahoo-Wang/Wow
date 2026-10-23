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

package me.ahoo.wow.mongo.log

import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import org.bson.Document
import java.util.TreeSet
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The sequences one [MongoEventLog] hands its appends (decision D271), per partition.
 *
 * A node reserves a **block** of [blockSize] sequences with one update of the partition's counter, which records the
 * block as open, `{node, from, to, collections}`, and allocates from it in memory. `collections` are the aggregate
 * collections the block may be written to: the ones registered when it was reserved, and the one it was reserved for.
 * An append to a collection outside the list, or one that must land after a sequence beyond the block's next (an
 * aggregate's previous stream, written by another node), takes a new block: the current one is closed at its next
 * sequence, so its remainder is final without placeholders ([Closing]).
 *
 * Every allocated sequence is **settled** by the log: written, or a placeholder written at it. A block leaves the
 * counter's list once it is used up or closed and every sequence it gave is settled; until then it stays, and the
 * partition's tail can resolve a sequence it waits at ([resolve]): its own unallocated remainder is closed, an
 * allocated sequence whose placeholder could not be written is filled again, another node's block is the log's to seal
 * with placeholders (a node that stopped, or a zombie, may still write it, and the placeholders take its sequences
 * first). A node whose block another node sealed meets a taken position on its next insert and drops the block
 * ([abandon]).
 *
 * [node] identifies this log's process for its blocks only; a restarted node has another, so its former blocks are
 * sealed like any stopped node's.
 */
internal class MongoSequencer(
    /** The partition counters, written with a majority's acknowledgement. */
    private val counters: MongoCollection<Document>,
    val node: String,
    private val blockSize: Long,
    partitions: Int,
) {
    init {
        require(blockSize >= 1) { "A block holds at least one sequence." }
    }

    private val lanes = List(partitions) { Lane(it) }

    /**
     * A sequence of [partition] for an append to [collection], greater than [after] (the aggregate's previous stream,
     * 0 for none). [registered] gives the collections a new block may be written to. The caller writes
     * [Allocation.closing] first when there is one, then the stream, and [settle]s both.
     */
    fun allocate(partition: Int, collection: String, after: Long, registered: () -> Collection<String>): Allocation =
        lanes[partition].allocate(collection, after, registered)

    /** [sequence] of [partition] is written, or has its placeholder. */
    fun settle(partition: Int, sequence: Long) = lanes[partition].settle(sequence)

    /** [closing] is written. */
    fun settle(closing: Closing) = lanes[closing.partition].settle(closing.from)

    /**
     * [sequence] of [partition], allocated to an append to [collection], could not be written nor filled: [resolve]
     * fills it again later, in that collection, where the stream would have landed.
     */
    fun unfilled(partition: Int, sequence: Long, collection: String) =
        lanes[partition].unfilled(sequence, collection)

    /** Another node sealed the block [sequence] of [partition] came from: this node allocates from it no more. */
    fun abandon(partition: Int, sequence: Long) = lanes[partition].abandon(sequence)

    /**
     * What the tail waiting at [sequence] of [partition] does about it, given the counter's open [blocks]: nothing yet
     * (an append of this node is in flight, or no block holds it), close this node's unallocated remainder, fill a
     * sequence of this node again, or seal another node's block from [sequence] on.
     */
    fun resolve(partition: Int, sequence: Long, blocks: List<OpenBlock>): Resolution {
        val holder = blocks.firstOrNull { sequence in it.from..it.to } ?: return Resolution.Wait
        return if (holder.node == node) lanes[partition].resolve(sequence) else Resolution.Seal(holder)
    }

    /** The open blocks of [partition], as the counter records them. */
    fun openBlocks(counter: Document?): List<OpenBlock> =
        counter?.getList(BLOCKS, Document::class.java).orEmpty().map(OpenBlock::of)

    /** Takes [block] out of [partition]'s list, once sealed. */
    fun remove(partition: Int, block: OpenBlock) {
        counters.updateOne(
            Filters.eq(MongoEventLog.ID, partition),
            Updates.pull(BLOCKS, Document(NODE, block.node).append(FROM, block.from)),
        )
    }

    /** One block of this node: its range, where it allocates next, and its sequences not settled yet. */
    private class Block(val from: Long, val to: Long, val collections: Set<String>) {
        var next = from
        var closed = false
        val pending = TreeSet<Long>()
        val unfilled = HashMap<Long, String>()

        val done: Boolean get() = (closed || next > to) && pending.isEmpty()
    }

    private inner class Lane(private val partition: Int) {
        private val lock = ReentrantLock()
        private var current: Block? = null

        /** Blocks this node no longer allocates from, until every sequence they gave is settled. */
        private val retired = ArrayList<Block>()

        fun allocate(collection: String, after: Long, registered: () -> Collection<String>): Allocation {
            val done = ArrayList<Block>()
            val allocation = lock.withLock {
                var closing: Closing? = null
                val usable = current?.takeIf { it.next <= it.to && collection in it.collections && it.next > after }
                val block = usable ?: run {
                    closing = current?.let(::retire)
                    retired.filter { it.done }.forEach { retired -= it; done += it }
                    reserve(registered() + collection).also { current = it }
                }
                val sequence = block.next++
                block.pending += sequence
                Allocation(partition, sequence, closing)
            }
            done.forEach(::remove)
            return allocation
        }

        /** Stops allocating from [block]; its remainder, when it has one, is to be closed, which it then waits for. */
        private fun retire(block: Block): Closing? {
            retired += block
            if (current === block) current = null
            if (block.next > block.to) return null
            block.closed = true
            block.pending += block.next
            return Closing(partition, block.next, block.to, block.collections.first())
        }

        private fun remove(block: Block) =
            remove(partition, OpenBlock(node, block.from, block.to, block.collections.toList()))

        private fun reserve(collections: Collection<String>): Block {
            val names = collections.distinct()
            val add = Document(NODE, node)
                .append(FROM, Document("\$add", listOf("\$${MongoEventLog.SEQUENCE}", 1L)))
                .append(TO, Document("\$add", listOf("\$${MongoEventLog.SEQUENCE}", blockSize)))
                .append(COLLECTIONS, names)
            // One pipeline update: both fields are computed from the counter as it was, so the block is the next range.
            val open = Document("\$ifNull", listOf("\$$BLOCKS", emptyList<Any>()))
            val blocks = Document("\$concatArrays", listOf(open, listOf(add)))
            val sequence = Document("\$add", listOf("\$${MongoEventLog.SEQUENCE}", blockSize))
            val update = listOf(Document("\$set", Document(BLOCKS, blocks).append(MongoEventLog.SEQUENCE, sequence)))
            val to = counters.findOneAndUpdate(
                Filters.eq(MongoEventLog.ID, partition),
                update,
                FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
            )!!.getLong(MongoEventLog.SEQUENCE)
            return Block(to - blockSize + 1, to, names.toSet())
        }

        fun settle(sequence: Long) {
            val done = lock.withLock {
                val block = block(sequence) ?: return
                block.pending -= sequence
                block.unfilled -= sequence
                if (block.done && block in retired) block.also { retired -= it } else null
            }
            done?.let(::remove)
        }

        fun unfilled(sequence: Long, collection: String) =
            lock.withLock { block(sequence)?.unfilled?.put(sequence, collection) }

        fun abandon(sequence: Long) = lock.withLock {
            val block = block(sequence) ?: return@withLock
            if (block === current) {
                current = null
                retired += block
            }
            block.closed = true
        }

        fun resolve(sequence: Long): Resolution = lock.withLock {
            val block = block(sequence) ?: return Resolution.Wait
            when {
                sequence in block.unfilled -> Resolution.Fill(block.unfilled.getValue(sequence))
                sequence >= block.next && block === current -> Resolution.Close(checkNotNull(retire(block)))

                else -> Resolution.Wait
            }
        }

        private fun block(sequence: Long): Block? =
            (listOfNotNull(current) + retired).firstOrNull { sequence in it.from..it.to }
    }

    /** A sequence for one append, and the remainder of the block it replaced, to be closed first. */
    class Allocation(val partition: Int, val sequence: Long, val closing: Closing?)

    /** A block's remainder that no sequence was allocated from: written as one range placeholder, it is final. */
    class Closing(val partition: Int, val from: Long, val to: Long, val collection: String)

    /** An open block of the counter's list. */
    data class OpenBlock(val node: String, val from: Long, val to: Long, val collections: List<String>) {
        companion object {
            fun of(document: Document) = OpenBlock(
                document.getString(NODE),
                document.getLong(FROM),
                document.getLong(TO),
                document.getList(COLLECTIONS, String::class.java),
            )
        }
    }

    /** What the tail does about a sequence it waits at. */
    sealed interface Resolution {
        data object Wait : Resolution

        class Close(val closing: Closing) : Resolution

        /** Fill the sequence in [collection], the one its append wrote to. */
        class Fill(val collection: String) : Resolution

        class Seal(val block: OpenBlock) : Resolution
    }

    internal companion object {
        const val BLOCKS = "blocks"
        const val NODE = "node"
        const val FROM = "from"
        const val TO = "to"
        const val COLLECTIONS = "collections"
    }
}
