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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.spi.timer.Timer
import org.junit.jupiter.api.Test

class MongoTimerMergeTest {
    @Test
    fun `ids at one due time are ordered by code point, as MongoDB orders strings`() {
        // U+FFFD sorts before U+1F600 by code point, after its UTF-16 surrogates (U+D83D) by char.
        val replacement = "�"
        val emoji = "😀"
        (emoji < replacement).assert().isTrue()

        MongoTimerMerge.merged(listOf(timer(emoji, 0, 5), timer(replacement, 0, 5)), perPartition = null)
            .map { it.id }.assert().containsExactly(replacement, emoji)
    }

    @Test
    fun `an id sorts after its prefix`() {
        MongoTimerMerge.codePoints("a", "ab").assert().isNegative()
        MongoTimerMerge.codePoints("ab", "a").assert().isPositive()
        MongoTimerMerge.codePoints("ab", "ab").assert().isZero()
    }

    @Test
    fun `the timers of several reads come in due order, each partition cut to its first`() {
        // Two reads, each with the first two of each partition among its own unions.
        val first = listOf(timer("a-1", 0, 40), timer("a-2", 0, 50), timer("b-1", 1, 10))
        val second = listOf(timer("a-3", 0, 20), timer("a-4", 0, 60), timer("b-2", 1, 30), timer("b-3", 1, 35))

        val merged = MongoTimerMerge.merged(first + second, perPartition = 2)

        merged.map { it.id }.assert().containsExactly("b-1", "a-3", "b-2", "a-1")
    }

    @Test
    fun `without a limit every timer is kept, in due order`() {
        val timers = listOf(timer("c", 0, 30), timer("a", 1, 10), timer("b", 0, 10))

        MongoTimerMerge.merged(timers, perPartition = null).map { it.id }.assert().containsExactly("a", "b", "c")
    }

    private fun timer(id: String, partition: Int, dueAt: Long): Timer {
        val ref = AggregateType("ctx", "order").ref("t", id)
        return Timer(
            id = id,
            owner = ref,
            key = "k",
            version = 1,
            partition = partition,
            rule = ScheduleRule.At(dueAt),
            dueAt = dueAt,
            target = ref,
            commandType = "c.order.Cancel",
            body = "{}",
        )
    }
}
