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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContract
import me.ahoo.wow.analytics.catalog.api.source.SyncSource
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseCatalog
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.WowProblemException
import org.springframework.context.SmartLifecycle
import java.time.Duration

/**
 * The change detection of the catalog: every [interval], it lists each database's tables and
 * views (their uuid, metadata modification time and a fingerprint of their columns, `system.tables` and
 * `system.columns`: the time is in seconds, so an `ALTER` in the second of the last look shows only in the columns)
 * and when each last got a `wow_catalog` row, and
 * sends `SyncSource` for each table whose listing changed since it last looked, or that is gone: the source's
 * aggregate reads the table again and applies the trust rules. Its first look, at startup, syncs every table, which
 * changes nothing where nothing changed.
 *
 * It sends through the node's [CommandClient] as [CALLER], a catalog administrator (`SyncSource` reads only what the warehouse says, so whoever sends
 * it changes nothing the warehouse does not). A failed look is logged and tried again at the next one.
 */
class CatalogWatcher(
    private val catalog: WarehouseCatalog,
    private val commands: () -> CommandClient,
    private val interval: Duration,
) : SmartLifecycle {
    private val seen = HashMap<String, String>()
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    /** Looks at the warehouse once: the tables whose listing changed since the last look are synced. */
    suspend fun look(): Int {
        var synced = 0
        catalog.databases.forEach { database ->
            val changes = catalog.catalogChanges(database)
            val now = catalog.tables(database).associate { table ->
                "$database\u0000${table.name}" to
                    "${table.uuid}|${table.modifiedAt}|${table.columns}|${changes[table.name].orEmpty()}"
            }
            val gone = seen.keys.filter { it.startsWith("$database\u0000") && it !in now }
            val changed = now.filter { (key, listing) -> seen[key] != listing }.keys + gone
            changed.forEach { key ->
                if (sync(database, key.substringAfter('\u0000'))) {
                    synced++
                    now[key]?.let { seen[key] = it } ?: seen.remove(key)
                }
            }
        }
        return synced
    }

    /** Sends `SyncSource` of [database]'s [table]; whether it succeeded, so a failed one is sent again next time. */
    private suspend fun sync(database: String, table: String): Boolean {
        val ref = AnalyticsCatalogContract.Source.ref(
            CALLER.tenantId,
            AnalyticsCatalogContext.sourceIdOf(catalog.connection, database, table)
        )
        return try {
            commands().of(CALLER).wait(SyncSource(database, table), ref)
            true
        } catch (rejected: CommandRejectedException) {
            logger.warn { "SyncSource of $database.$table was rejected: ${rejected.code} ${rejected.result.error?.message}" }
            false
        } catch (failed: WowProblemException) {
            logger.warn { "SyncSource of $database.$table did not succeed: ${failed.code} ${failed.message}" }
            false
        }
    }

    override fun start() {
        val started = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = started
        job = started.launch {
            while (isActive) {
                try {
                    look()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (
                    @Suppress("TooGenericExceptionCaught") // Any failure of a look is tried again at the next.
                    failure: Exception,
                ) {
                    logger.warn(failure) { "The catalog could not look at the warehouse; it tries again in $interval." }
                }
                delay(interval.toMillis())
            }
        }
    }

    override fun stop() {
        scope?.cancel()
        scope = null
        job = null
    }

    override fun isRunning(): Boolean = job?.isActive == true

    /** After the node and its public server started: the node takes commands by then. */
    override fun getPhase(): Int = SmartLifecycle.DEFAULT_PHASE

    companion object {

        /** The catalog administrator the watcher sends as. */
        @JvmField
        val CALLER: Caller = Caller.of(
            tenantId = AnalyticsCatalogContext.TENANT_ID,
            scope = Scope.NONE,
            subject = "analytics-catalog-watcher",
            attributes = mapOf(AnalyticsCatalogRoles.ROLES_ATTRIBUTE to listOf(AnalyticsCatalogRoles.ADMIN)),
        )

        private val logger = KotlinLogging.logger {}
    }
}
