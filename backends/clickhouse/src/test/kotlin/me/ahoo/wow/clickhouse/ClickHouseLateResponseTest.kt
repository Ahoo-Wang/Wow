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

package me.ahoo.wow.clickhouse

import com.clickhouse.client.api.Client
import com.clickhouse.client.api.command.CommandResponse
import com.clickhouse.client.api.query.QueryResponse
import com.clickhouse.client.api.query.QuerySettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.clickhouse.query.ClickHouseExecutor
import me.ahoo.wow.clickhouse.query.ClickHouseStatement
import me.ahoo.wow.clickhouse.sink.ClientStatements
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A response that arrives after its wait was cancelled is closed, since nothing else holds it (#5796): a client with
 * asynchronous operations answers with a future still pending, as these do.
 */
class ClickHouseLateResponseTest {
    /** Completed once the client was asked: the wait has begun. */
    private val asked = CompletableDeferred<Unit>()
    private val query = CompletableFuture<QueryResponse>()
    private val command = CompletableFuture<CommandResponse>()
    private val client = mockk<Client>(relaxed = true) {
        every { query(any<String>(), any<Map<String, Any>>(), any<QuerySettings>()) } answers {
            asked.complete(Unit)
            query
        }
        // The KILL QUERY of a cancelled statement.
        every { query(any<String>(), any<Map<String, Any>>()) } returns
            CompletableFuture.completedFuture(mockk(relaxed = true))
        every { execute(any<String>()) } answers {
            asked.complete(Unit)
            command
        }
    }

    @Test
    fun `a dataset query's response that arrives after the query was cancelled should be closed`(): Unit =
        runBlocking {
            val late = mockk<QueryResponse>(relaxed = true)
            val reading = launch(Dispatchers.Default) {
                ClickHouseExecutor(client).rows(ClickHouseStatement("SELECT 1")).collect()
            }
            asked.await()
            reading.cancelAndJoin()
            query.complete(late)
            verify(exactly = 1) { late.close() }
        }

    @Test
    fun `a late response that fails to close should still be closed once, and report nothing`(): Unit = runBlocking {
        val late = mockk<CommandResponse> { every { close() } throws IllegalStateException("Failed to close response") }
        val reported = CopyOnWriteArrayList<Throwable>()
        val handler = CoroutineExceptionHandler { _, failure -> reported += failure }
        val executing = launch(Dispatchers.Default + handler) { ClientStatements(client).execute("CREATE TABLE t") }
        asked.await()
        executing.cancelAndJoin()
        command.complete(late)
        verify(exactly = 1) { late.close() }
        reported.assert().isEmpty()
    }

    @Test
    fun `a response the wait gets should be the caller's, unclosed, and a failure the client's own`(): Unit =
        runBlocking {
            val response = mockk<QueryResponse>(relaxed = true)
            CompletableFuture.completedFuture(response).awaitResponse().assert().isSameAs(response)
            verify(exactly = 0) { response.close() }
            val refused = IllegalStateException("refused")
            // An asynchronous operation's future fails wrapped in a CompletionException; a future may fail bare too.
            listOf(CompletionException(refused), refused).forEach { failure ->
                val failed = CompletableFuture<QueryResponse>().apply { completeExceptionally(failure) }
                // Unwrapped: the client's own exception, as the translation of failures expects it.
                runCatching { failed.awaitResponse() }.exceptionOrNull().assert()
                    .isInstanceOf(IllegalStateException::class.java).hasMessage("refused")
            }
        }

    @Test
    fun `a statement's response that arrives after the statement was cancelled should be closed`(): Unit =
        runBlocking {
            val late = mockk<CommandResponse>(relaxed = true)
            val executing = launch(Dispatchers.Default) { ClientStatements(client).execute("CREATE TABLE t") }
            asked.await()
            executing.cancelAndJoin()
            command.complete(late)
            verify(exactly = 1) { late.close() }
        }
}
