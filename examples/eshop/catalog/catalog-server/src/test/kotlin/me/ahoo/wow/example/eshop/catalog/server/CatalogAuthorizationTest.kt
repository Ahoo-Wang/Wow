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

package me.ahoo.wow.example.eshop.catalog.server

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.ledger.RetryParked
import me.ahoo.wow.api.ledger.SkipParked
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.schedule.EnableJob
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.api.schedule.SetSchedule
import me.ahoo.wow.example.eshop.catalog.api.brand.CreateBrand
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.spi.snapshot.RebuildSnapshot
import me.ahoo.wow.spi.snapshot.SnapshotRebuilds
import org.junit.jupiter.api.Test

/** The catalog's authorizer: who changes the catalog, and who sends Wow's operational commands. */
class CatalogAuthorizationTest {
    private val authorizer: CommandAuthorizer = CatalogAuthorization().catalogAuthorizer()
    private val type = AggregateType("eshop-catalog", "brand")

    /** The callers of the tenant `acme`: a buyer acts in its own scope, the others in the tenant's. */
    private val callers: Map<String, Caller> = mapOf(
        "buyer" to Caller.owner("alice", "acme"),
        "employee" to Caller.of("acme", subject = "emma"),
        "service" to Caller.ofService("eshop-basket", "acme"),
        "anonymous" to Caller.of("acme"),
    )

    /** The commands, by a short name: the domain's, an aggregate's lifecycle and schedule, and the operational ones. */
    private val commands: Map<String, CommandType> = mapOf(
        "create" to CommandType.of("eshop-catalog.brand.CreateBrand", CreateBrand::class.java),
        "delete" to CommandType.of(Lifecycle.DELETE_AGGREGATE, DeleteAggregate::class.java),
        "set-schedule" to CommandType.of(Schedules.SET_SCHEDULE, SetSchedule::class.java),
        "enable-job" to CommandType.of(ScheduleJobs.ENABLE_JOB, EnableJob::class.java),
        "rebuild" to rebuild(),
        "ledger-retry" to CommandType.of(Ledger.RETRY_PARKED, RetryParked::class.java),
        "ledger-skip" to CommandType.of(Ledger.SKIP_PARKED, SkipParked::class.java),
        "ledger-read" to CommandType.of(Ledger.READ, Any::class.java),
    )

    /** The snapshot rebuild's type: its class is Wow's own, which no application names but a test of its authorizer. */
    @OptIn(InternalWowApi::class)
    private fun rebuild() = CommandType.of(SnapshotRebuilds.REBUILD_SNAPSHOT, RebuildSnapshot::class.java)

    /** Who is allowed each command: every caller acting in its own scope, on an aggregate of its tenant. */
    private fun allowed(): Map<String, Set<String>> = commands.mapValues { (_, command) ->
        callers.filter { (_, caller) ->
            val action = CommandAction.of(command, type.ref("acme", "a-1"), caller.scope, Creation.NONE)
            runBlocking { authorizer.authorize(caller, action) } is Authorization.Allowed
        }.keys
    }

    @Test
    fun `a buyer is refused the operational commands and keeps its own aggregate's, the rest as before`() {
        allowed().assert().isEqualTo(
            mapOf(
                "create" to setOf("employee", "service", "anonymous"),
                "delete" to setOf("employee", "service", "anonymous"),
                "set-schedule" to setOf("employee", "service", "anonymous"),
                "enable-job" to setOf("employee", "service"),
                "rebuild" to setOf("employee", "service"),
                "ledger-retry" to setOf("employee", "service"),
                "ledger-skip" to setOf("employee", "service"),
                "ledger-read" to setOf("employee", "service"),
            ),
        )
    }
}
