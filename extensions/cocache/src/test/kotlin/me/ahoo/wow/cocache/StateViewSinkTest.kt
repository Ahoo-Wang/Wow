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

package me.ahoo.wow.cocache

import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.cocache.api.StateKey
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.spi.state.StateView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration

/** The owner's sink: each commit's state, as its view at its version, a deleting commit as a tombstone. */
class StateViewSinkTest {
    private val runtime = runtimeOn(InMemoryBackend())
    private val caches = StateViewCaches(StateViewCaches.CacheFactory.of(runtime)) { error("no miss is read") }
    private val sink = StateViewSink(OrderView::class.java, caches.writerOf(OrderView::class.java))
    private val key = StateKey("t", "o-1")

    @AfterEach
    fun close() {
        runtime.close()
    }

    private fun recordOf(version: Int, state: String?, deleted: Boolean = false): StateEventRecord = StateEventRecord.of(
        ref = ORDER.ref("t", "o-1"),
        version = version,
        commandId = "c-$version",
        requestId = "r-$version",
        commandType = "shop.order.Change",
        commitTime = 0,
        events = listOf(StateEventPayload.of("Changed", 1, WowJson.mapper.readTree("{}"))),
        state = state?.let { StateView.of(version, "f", WowJson.mapper.readTree(it)) },
        deleted = deleted,
    )

    @Test
    fun `each commit should write its state, a later delivery of an older one nothing`() = runBlocking<Unit> {
        sink.write(listOf(recordOf(1, """{"item":"book","quantity":1}"""), recordOf(2, """{"item":"pen","added":1}""")))
        caches.of<OrderView>().get(key).assert().isEqualTo(OrderView("pen"))
        sink.write(listOf(recordOf(1, """{"item":"book"}""")))
        caches.of<OrderView>().get(key).assert().isEqualTo(OrderView("pen"))
    }

    @Test
    fun `a deleting commit should write a tombstone that a redelivered older commit does not undo`() =
        runBlocking<Unit> {
            sink.write(listOf(recordOf(1, """{"item":"book"}"""), recordOf(2, null, deleted = true)))
            caches.of<OrderView>().get(key).assert().isNull()
            sink.write(listOf(recordOf(1, """{"item":"book"}""")))
            caches.of<OrderView>().get(key).assert().isNull()
            sink.toString().assert().contains("wow.shop.order")
        }

    @Test
    fun `a record without its state should fail the write`() {
        assertThrownBy<IllegalStateException> { runBlocking { sink.write(listOf(recordOf(1, null))) } }
    }

    @Test
    fun `states written for a lifetime of their own should be read as written`() = runBlocking<Unit> {
        val writer = caches.writerOf(OrderView::class.java, Duration.ofMinutes(5))
        StateViewSink(OrderView::class.java, writer).write(
            listOf(
                recordOf(1, """{"item":"book"}"""),
                recordOf(2, null, deleted = true),
                recordOf(3, """{"item":"pen"}""")
            ),
        )
        caches.of<OrderView>().get(key).assert().isEqualTo(OrderView("pen"))
        writer.delete(key, 4).assert().isTrue()
        caches.of<OrderView>().get(key).assert().isNull()
    }
}
