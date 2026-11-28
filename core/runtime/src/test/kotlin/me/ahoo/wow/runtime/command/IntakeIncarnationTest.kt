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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.source.TopicIncarnationException
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.transport.PartitionedSource
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/** The incarnation a context's command intake reads, resolved once, and the checkpoints and reads it gives the intake. */
class IntakeIncarnationTest {
    private val topics = InMemoryTopics(1).also { it.create(TOPIC, 1) }
    private val store = InMemoryCheckpointStore()

    private val told = object : PartitionedSource by topics {
        @Volatile
        var incarnation = A
        val asked = AtomicInteger()

        override suspend fun incarnation(topic: String): String = incarnation.also { asked.incrementAndGet() }
    }

    @Test
    fun `the intake's checkpoints are kept under its incarnation's group, resolved once, and its marker recorded`(): Unit =
        runBlocking {
            val intake = IntakeIncarnation(told, TOPIC, GROUP, store)
            intake.incarnation.assert().isNull()
            intake.checkpoints.save(GROUP, CommandIntake.NAME, 0, 4)
            intake.checkpoints.saveAll(GROUP, listOf(Checkpoint.of(CommandIntake.NAME, 0, 5)))
            intake.checkpoints.load(GROUP, CommandIntake.NAME, 0).assert().isEqualTo(5L)
            intake.checkpoints.list(GROUP).assert().containsExactly(Checkpoint.of(CommandIntake.NAME, 0, 5))
            val saved = Checkpoint.of(CommandIntake.NAME, 0, 5)
            intake.checkpoints.list(GROUP, CommandIntake.NAME).assert().containsExactly(saved)
            intake.incarnation.assert().isEqualTo(A)
            told.asked.get().assert().isEqualTo(1)
            store.load("$GROUP#$A", CommandIntake.NAME, 0).assert().isEqualTo(5L)
            store.list(GROUP).map { it.subscription }.assert().containsExactly("wow.incarnation#$A")
            // Another group is the store's as it is.
            intake.checkpoints.save("other", CommandIntake.NAME, 0, 1)
            store.load("other", CommandIntake.NAME, 0).assert().isEqualTo(1L)
            intake.entries(1, Observer.NONE).incarnation.assert().isEqualTo(A)
        }

    @Test
    fun `a read of the command topic created again is refused, and one of a source that tells none is not checked`():
        Unit = runBlocking {
        val intake = IntakeIncarnation(told, TOPIC, GROUP, store)
        intake.resolve().assert().isEqualTo(A)
        intake.guarded.read(TOPIC, 0, 0, 0).toList().assert().isEmpty()
        told.incarnation = B
        assertThrownBy<TopicIncarnationException> { runBlocking { intake.guarded.read(TOPIC, 0, 0, 1).toList() } }
            .hasMessageContaining("incarnation $B now").hasMessageContaining("join $B, when its node starts again.")
        intake.resolve().assert().isEqualTo(A)

        val untold = IntakeIncarnation(topics, TOPIC, GROUP, store)
        untold.checkpoints.save(GROUP, CommandIntake.NAME, 1, 2)
        untold.incarnation.assert().isNull()
        store.load(GROUP, CommandIntake.NAME, 1).assert().isEqualTo(2L)
        untold.guarded.offsets(TOPIC, 0).end.assert().isEqualTo(0L)
    }

    private companion object {
        const val TOPIC = "test.wow.stock.commands"
        const val GROUP = "stock-service/stock"
        const val A = "incarnation-a"
        const val B = "incarnation-b"
    }
}
