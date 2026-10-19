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

package me.ahoo.wow.spi.log

/**
 * Where a log partition comes from (decision D240): the metadata format 1 reserves for the online splits of 10.x, so
 * that they are an additive change (decision D59). Each backend stores it with the partition's counter, writes
 * [ROOT] for every partition a log is created with, and reads it back with [EventLog.lineage]; nothing acts on it yet.
 *
 * - [parent] is the partition this one split from, null for a partition the log was created with. When P doubles,
 *   partition p splits into p and p + P by the next bit of the slot ([Partitioner.SLOTS]); p + P names p its parent.
 * - [epoch] counts the splits that made the partition: 0 for one the log was created with, one more than its
 *   parent's for a child.
 *
 * Fields may be added (decisions D59, D228): construct one with [of]; the constructor is internal to Wow.
 */
class PartitionLineage private constructor(val parent: Int?, val epoch: Int) {
    init {
        require(parent == null || parent >= 0) { "A parent partition is never negative." }
        require(epoch >= 0) { "A partition's epoch is never negative." }
        require(parent != null || epoch == 0) { "A partition without a parent is of epoch 0." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is PartitionLineage && parent == other.parent && epoch == other.epoch

    override fun hashCode(): Int = 31 * (parent ?: -1) + epoch

    override fun toString(): String = "PartitionLineage(parent=$parent, epoch=$epoch)"

    companion object {
        /** The lineage of a partition the log was created with: no parent, epoch 0. */
        @JvmField
        val ROOT: PartitionLineage = PartitionLineage(null, 0)

        /** A PartitionLineage of the given fields; [ROOT] when there is no parent. */
        @JvmStatic
        fun of(parent: Int?, epoch: Int): PartitionLineage =
            if (parent == null && epoch == 0) ROOT else PartitionLineage(parent, epoch)
    }
}
