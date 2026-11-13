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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** [DoneBlocks]: a block read through is taken out only after the grace, so its node's reservation goes first. */
class DoneBlocksTest {
    private val time = TestTimeSource()
    private val blocks = DoneBlocks(GRACE, time)

    private fun block(node: String, from: Long) = MongoSequencer.OpenBlock(node, from, from + SIZE - 1, listOf("order"))

    @Test
    fun `a block the read has not gone through should never be due`() {
        val open = block("a", 1)
        blocks.due(0, listOf(open), through = SIZE - 1).assert().isEmpty()
        time += GRACE * 2
        blocks.due(0, listOf(open), through = SIZE - 1).assert().isEmpty()
    }

    @Test
    fun `a stopped node's block read through should be due once the grace passed, and once`() {
        val stopped = block("a", 1)
        blocks.due(0, listOf(stopped), through = SIZE).assert().isEmpty()
        time += GRACE - 1.milliseconds
        blocks.due(0, listOf(stopped), through = SIZE).assert().isEmpty()
        time += 1.milliseconds
        blocks.due(0, listOf(stopped), through = SIZE).assert().containsExactly(stopped)
        // Taken out: a read that still finds it listed (its pull lost) starts a new grace.
        blocks.due(0, listOf(stopped), through = SIZE).assert().isEmpty()
    }

    @Test
    fun `a block its node took out within the grace should be forgotten, never due`() {
        val live = block("a", 1)
        blocks.due(0, listOf(live), through = SIZE).assert().isEmpty()
        time += GRACE / 2
        // Its node's next reservation took it out: the next read lists it no more.
        blocks.due(0, emptyList(), through = SIZE * 2).assert().isEmpty()
        time += GRACE
        // Were it listed again, its grace would start over: nothing of the first sighting is kept.
        blocks.due(0, listOf(live), through = SIZE * 2).assert().isEmpty()
    }

    @Test
    fun `the blocks of nodes that stopped one after another should all be due after the grace`() {
        val stopped = (0 until STOPS).map { block("n$it", it * SIZE + 1) }
        stopped.indices.forEach { seen -> blocks.due(0, stopped.take(seen + 1), through = (seen + 1) * SIZE) }
        time += GRACE
        blocks.due(0, stopped, through = STOPS * SIZE).assert().containsExactlyElementsOf(stopped)
    }

    @Test
    fun `each partition's blocks should be kept apart`() {
        val zero = block("a", 1)
        val one = block("a", 1)
        blocks.due(0, listOf(zero), through = SIZE)
        time += GRACE
        blocks.due(1, listOf(one), through = SIZE).assert().isEmpty()
        blocks.due(0, listOf(zero), through = SIZE).assert().containsExactly(zero)
    }

    private companion object {
        val GRACE = 30.seconds
        const val SIZE = 256L
        const val STOPS = 3
    }
}
