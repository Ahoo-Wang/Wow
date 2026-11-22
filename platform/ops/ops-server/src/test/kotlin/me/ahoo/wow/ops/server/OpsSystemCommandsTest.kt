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

package me.ahoo.wow.ops.server

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
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.ops.api.parked.RecordParked
import me.ahoo.wow.ops.api.parked.Retry
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.spi.snapshot.RebuildSnapshot
import me.ahoo.wow.spi.snapshot.SnapshotRebuilds
import org.junit.jupiter.api.Test

/**
 * The ops service's authorizers as its node asks them ([CommandAuthorizer.allOf]): Wow's system commands, the
 * operational ones and an aggregate's lifecycle alike, draw no objection of [OpsAuthorizer], and the operator role
 * decides them, as it decides an operator's actions; no caller sends a record.
 */
class OpsSystemCommandsTest {
    private val node = CommandAuthorizer.allOf(listOf(OpsAuthorizer(), OperatorRole()))
    private val parked = AggregateType("ops", "parked")

    private val callers: Map<String, Caller> = mapOf(
        "buyer" to Caller.owner("alice", "acme"),
        "operator" to Caller.of(
            "acme",
            subject = "olga",
            attributes = mapOf(OpsRoles.ROLES_ATTRIBUTE to listOf(OpsRoles.OPERATOR)),
        ),
        "viewer" to Caller.of(
            "acme",
            subject = "victor",
            attributes = mapOf(OpsRoles.ROLES_ATTRIBUTE to listOf(OpsRoles.VIEWER)),
        ),
    )

    /** The snapshot rebuild's type: its class is Wow's own, which no application names but a test of its authorizer. */
    @OptIn(InternalWowApi::class)
    private fun rebuild() = CommandType.of(SnapshotRebuilds.REBUILD_SNAPSHOT, RebuildSnapshot::class.java)

    private val commands: Map<String, CommandType> = mapOf(
        "retry" to CommandType.of("ops.parked.Retry", Retry::class.java),
        "record" to CommandType.of("ops.parked.RecordParked", RecordParked::class.java),
        "delete" to CommandType.of(Lifecycle.DELETE_AGGREGATE, DeleteAggregate::class.java),
        "enable-job" to CommandType.of(ScheduleJobs.ENABLE_JOB, EnableJob::class.java),
        "rebuild" to rebuild(),
        "ledger-retry" to CommandType.of(Ledger.RETRY_PARKED, RetryParked::class.java),
        "ledger-skip" to CommandType.of(Ledger.SKIP_PARKED, SkipParked::class.java),
        "ledger-read" to CommandType.of(Ledger.READ, Any::class.java),
    )

    private fun allowed(): Map<String, Set<String>> = commands.mapValues { (_, command) ->
        callers.filter { (_, caller) ->
            val action = CommandAction.of(command, parked.ref("acme", "p-1"), caller.scope, Creation.NONE)
            runBlocking { node.authorize(caller, action) } is Authorization.Allowed
        }.keys
    }

    @Test
    fun `only an operator sends the system commands and acts on an entry, and no one sends a record`() {
        val operator = setOf("operator")
        allowed().assert().isEqualTo(
            mapOf(
                "retry" to operator,
                "record" to emptySet(),
                "delete" to operator,
                "enable-job" to operator,
                "rebuild" to operator,
                "ledger-retry" to operator,
                "ledger-skip" to operator,
                "ledger-read" to operator,
            ),
        )
    }
}
