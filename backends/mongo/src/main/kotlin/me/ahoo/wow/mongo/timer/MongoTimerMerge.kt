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

package me.ahoo.wow.mongo.timer

import me.ahoo.wow.spi.timer.Timer

/**
 * The merge of the due read ([MongoTimerStore.due]), whose unions go in several aggregations (#6743): what they read,
 * as one aggregation of all their unions gives it. That aggregation sorts by the due time, then the timer's id, as
 * MongoDB compares strings without a collation (by code point), and keeps the first timers of each partition.
 */
internal object MongoTimerMerge {
    /** The due read's order in memory: the due time, then the id by code point ([codePoints]). */
    val DUE: Comparator<Timer> = Comparator { left, right ->
        left.dueAt.compareTo(right.dueAt).takeIf { it != 0 } ?: codePoints(left.id, right.id)
    }

    /**
     * [timers], read by several aggregations, in [DUE] order, and with [perPartition] the first [perPartition] of each
     * partition. Each aggregation kept the first [perPartition] of each partition among the timers its own unions
     * read, so it kept every timer of the partition's first [perPartition] overall that it read: cutting the merge
     * keeps the timers one aggregation would. The timers of one aggregation come back as they were.
     */
    fun merged(timers: List<Timer>, perPartition: Int?): List<Timer> {
        val ordered = timers.sortedWith(DUE)
        perPartition ?: return ordered
        val taken = HashMap<Int, Int>()
        return ordered.filter { taken.merge(it.partition, 1, Int::plus)!! <= perPartition }
    }

    /** [left] against [right] by code point: MongoDB's order of strings without a collation, and UTF-8's. */
    fun codePoints(left: String, right: String): Int {
        var i = 0
        var j = 0
        while (i < left.length && j < right.length) {
            val a = left.codePointAt(i)
            val b = right.codePointAt(j)
            if (a != b) {
                return a.compareTo(b)
            }
            i += Character.charCount(a)
            j += Character.charCount(b)
        }
        return (left.length - i).compareTo(right.length - j)
    }
}
