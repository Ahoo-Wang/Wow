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

package me.ahoo.wow.analytics.catalog.server

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.analytics.catalog.api.source.SyncSource
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseCatalog
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseTableInfo
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.RetryHint
import me.ahoo.wow.api.client.SendOptions
import me.ahoo.wow.api.client.WowProblemException
import me.ahoo.wow.api.result.CommandResult
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** The watcher sends `SyncSource` through the node's [CommandClient] as the catalog administrator (decision D257). */
class CatalogWatcherTest {
    private val sent = CopyOnWriteArrayList<Triple<Caller?, SyncSource, AggregateRef>>()

    /** Succeeds for table `ok`, rejects `bad`, fails `down` while [down]. */
    private inner class Commands(private val caller: Caller? = null) : CommandClient {
        var down = true

        override fun of(caller: Caller): CommandClient = Commands(caller).also { it.down = down }

        override suspend fun send(command: Any, ref: AggregateRef, options: SendOptions): CommandResult =
            error("The watcher waits.")

        override suspend fun wait(command: Any, ref: AggregateRef, options: SendOptions): CommandResult {
            val sync = command as SyncSource
            sent += Triple(caller, sync, ref)
            return when (sync.table) {
                "bad" -> throw CommandRejectedException(result(ref, CommandResult.Status.REJECTED))
                "down" -> if (down) {
                    throw WowProblemException("wow.storage.unavailable", 503, RetryHint(true, true), "Down.")
                } else {
                    result(ref, CommandResult.Status.SUCCEEDED)
                }

                else -> result(ref, CommandResult.Status.SUCCEEDED)
            }
        }

        override fun stream(command: Any, ref: AggregateRef, options: SendOptions): Flow<CommandResult> =
            error("The watcher waits.")
    }

    // A result as the node answers it; its constructor is Wow's own.
    private fun result(ref: AggregateRef, status: CommandResult.Status): CommandResult = mockk(relaxed = true) {
        every { this@mockk.ref } returns ref
        every { this@mockk.status } returns status
    }

    private val catalog = object : WarehouseCatalog by WarehouseCatalog.NONE {
        override val connection: String = "ch"
        override val databases: List<String> = listOf("db")

        override suspend fun tables(database: String): List<WarehouseTableInfo> =
            listOf("ok", "bad", "down").map { WarehouseTableInfo(it, "u-$it", "MergeTree", "0") }
    }

    @Test
    fun `a table is synced until its SyncSource succeeds, as the catalog administrator`(): Unit = runBlocking {
        val commands = Commands()
        val watcher = CatalogWatcher(catalog, { commands }, Duration.ofSeconds(1))
        watcher.look().assert().isEqualTo(1)
        sent.map { it.second.table }.assert().containsExactly("ok", "bad", "down")
        sent.all { it.first == CatalogWatcher.CALLER }.assert().isTrue()
        sent.first().third.assert().isEqualTo(
            me.ahoo.wow.api.aggregate.AggregateType(AnalyticsCatalogContext.NAME, AnalyticsCatalogContext.SOURCE)
                .ref(CatalogWatcher.CALLER.tenantId, AnalyticsCatalogContext.sourceIdOf("ch", "db", "ok")),
        )
        sent.clear()
        commands.down = false
        // The table that synced is not sent again; the two that did not are.
        watcher.look().assert().isEqualTo(1)
        sent.map { it.second.table }.assert().containsExactly("bad", "down")
    }
}
