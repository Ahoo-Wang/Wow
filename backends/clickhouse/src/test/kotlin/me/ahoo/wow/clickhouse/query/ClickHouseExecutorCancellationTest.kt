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

package me.ahoo.wow.clickhouse.query

import com.clickhouse.client.api.Client
import com.clickhouse.client.api.query.QueryResponse
import com.clickhouse.client.api.query.QuerySettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A cancelled statement is killed while every thread of the dispatcher its reads run on blocks in a statement: in the
 * client's call, or in reading a response already received (#6289). Here that dispatcher has two threads, held by two
 * statements, as `Dispatchers.IO` is once its parallelism's worth of reads block.
 */
internal class ClickHouseExecutorCancellationTest {
    private val readers = 2

    private fun killedWhileReadsBlock(blockInCall: Boolean): Unit = runBlocking {
        val entered = CountDownLatch(readers)
        // Released by the kills: the server ends a killed statement. The test's own release is only its cleanup.
        val release = CountDownLatch(1)
        val killed = AtomicInteger()
        val allKilled = CompletableDeferred<Unit>()
        val response = mockk<QueryResponse>(relaxed = true)
        val client = mockk<Client>()
        fun block() {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "the blocked statement was never released" }
        }
        every { response.inputStream } answers {
            if (blockInCall) {
                ByteArrayInputStream(byteArrayOf())
            } else {
                object : InputStream() {
                    override fun read(): Int {
                        block()
                        return -1
                    }
                }
            }
        }
        every { client.query(any<String>(), any<Map<String, Any>>(), any<QuerySettings>()) } answers {
            if (blockInCall) block()
            CompletableFuture.completedFuture(response)
        }
        every { client.query(match<String> { it.startsWith("KILL QUERY") }, any<Map<String, Any>>()) } answers {
            if (killed.incrementAndGet() == readers) {
                allKilled.complete(Unit)
                release.countDown()
            }
            CompletableFuture.completedFuture(mockk<QueryResponse>(relaxed = true))
        }
        Executors.newFixedThreadPool(readers).asCoroutineDispatcher().use { reads ->
            val executor = ClickHouseExecutor(client, dispatcher = reads)
            val jobs = List(readers) {
                launch(Dispatchers.Default) { executor.rows(ClickHouseStatement("SELECT slow()")).collect() }
            }
            try {
                entered.await(5, TimeUnit.SECONDS).assert().isTrue()
                jobs.forEach { it.cancel() }
                withTimeoutOrNull(5_000) { allKilled.await() }
                killed.get().assert().isEqualTo(readers)
            } finally {
                release.countDown()
                withTimeout(5_000) { jobs.joinAll() }
            }
        }
        verify(exactly = readers) { response.close() }
    }

    @Test
    fun `cancelled statements are killed while every read thread blocks reading their responses`() {
        killedWhileReadsBlock(blockInCall = false)
    }

    @Test
    fun `cancelled statements are killed while every read thread blocks in the client's call`() {
        killedWhileReadsBlock(blockInCall = true)
    }

    @Test
    fun `a statement read to its end yields every row, is closed and is not killed`(): Unit = runBlocking {
        val response = mockk<QueryResponse>(relaxed = true) {
            every { inputStream } returns ByteArrayInputStream("{\"id\":1}\n{\"id\":2}\n".toByteArray())
        }
        val client = mockk<Client> {
            every { query(any<String>(), any<Map<String, Any>>(), any<QuerySettings>()) } returns
                CompletableFuture.completedFuture(response)
        }
        ClickHouseExecutor(client).rows(ClickHouseStatement("SELECT id")).toList()
            .map { it.path("id").intValue() }.assert().containsExactly(1, 2)
        verify(exactly = 1) { response.close() }
        verify(exactly = 0) { client.query(any<String>(), any<Map<String, Any>>()) }
    }
}
