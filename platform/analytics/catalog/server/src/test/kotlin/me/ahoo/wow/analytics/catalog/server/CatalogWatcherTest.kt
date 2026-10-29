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
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContract
import me.ahoo.wow.analytics.catalog.api.source.SyncSource
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseCatalog
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseTableInfo
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.CommandResult
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** The watcher sends `SyncSource` through the node's [CommandClient] as the catalog administrator (decision D257). */
class CatalogWatcherTest {
    private val sent = CopyOnWriteArrayList<Pair<Caller?, CommandRequest<*>>>()

    /** Succeeds for table `ok`, rejects `bad`, fails `down` while [down]. */
    private inner class Commands(private val caller: Caller? = null) : CommandClient {
        var down = true

        override fun of(caller: Caller): CommandClient = Commands(caller).also { it.down = down }

        override suspend fun send(request: CommandRequest<*>): CommandResult = error("The watcher waits.")

        override suspend fun wait(request: CommandRequest<*>): CommandResult {
            val sync = request.command as SyncSource
            sent += caller to request
            val ref = AnalyticsCatalogContract.Source.ref(caller!!.tenantId, request.id!!)
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

        override fun stream(request: CommandRequest<*>): Flow<CommandResult> = error("The watcher waits.")
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
        sent.map { (it.second.command as SyncSource).table }.assert().containsExactly("ok", "bad", "down")
        sent.all { it.first == CatalogWatcher.CALLER }.assert().isTrue()
        // The source's type comes from its command's class; its id is the table's.
        sent.first().second.assert()
            .isEqualTo(CommandRequest.of(SyncSource("db", "ok"), AnalyticsCatalogContext.sourceIdOf("ch", "db", "ok")))
        sent.clear()
        commands.down = false
        // The table that synced is not sent again; the two that did not are.
        watcher.look().assert().isEqualTo(1)
        sent.map { (it.second.command as SyncSource).table }.assert().containsExactly("bad", "down")
    }

    @Test
    fun `a table whose columns change in the same second as the last look is synced again`(): Unit = runBlocking {
        val commands = Commands().apply { down = false }
        var fingerprint = "f-1"
        val altered = object : WarehouseCatalog by WarehouseCatalog.NONE {
            override val connection: String = "ch"
            override val databases: List<String> = listOf("db")

            // The metadata time stays the same second: only the columns' fingerprint tells the ALTER.
            override suspend fun tables(database: String): List<WarehouseTableInfo> =
                listOf(WarehouseTableInfo("ok", "u-ok", "MergeTree", "2026-10-20 10:00:00", fingerprint))
        }
        val watcher = CatalogWatcher(altered, { commands }, Duration.ofSeconds(1))
        watcher.look().assert().isEqualTo(1)
        watcher.look().assert().isEqualTo(0)
        fingerprint = "f-2"
        watcher.look().assert().isEqualTo(1)
    }
}
