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

package me.ahoo.wow.viewstore.server

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.ledger.RetryParked
import me.ahoo.wow.api.ledger.SkipParked
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.api.schedule.EnableJob
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.api.schedule.SetSchedule
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.spi.snapshot.RebuildSnapshot
import me.ahoo.wow.spi.snapshot.SnapshotRebuilds
import me.ahoo.wow.viewstore.api.ViewStoreContract
import me.ahoo.wow.viewstore.api.view.RenameView
import org.junit.jupiter.api.Test

/** Who sends Wow's operational commands to the view store, and that a user still writes and deletes its own views. */
class ViewStoreOperationalTest {
    private val authorizer = ViewStoreAuthorizer()

    private fun caller(owner: String?, vararg roles: String) = Caller.of(
        tenantId = "t1",
        scope = Scope(ownerId = owner),
        subject = owner ?: "operator",
        attributes = mapOf(ViewStoreRoles.ROLES_ATTRIBUTE to roles.toList()),
    )

    /** Each acts in its own scope: a user as its owner, the platform's operator as none. */
    private val callers: Map<String, Caller> = mapOf(
        "user" to caller("alice"),
        "system-writer" to caller("admin", ViewStoreRoles.SYSTEM_WRITER),
        "tenant-system-writer" to caller(null, ViewStoreRoles.SYSTEM_WRITER),
        "anonymous" to Caller.of("t1"),
    )

    /** The snapshot rebuild's type: its class is Wow's own, which no application names but a test of its authorizer. */
    @OptIn(InternalWowApi::class)
    private fun rebuild() = CommandType.of(SnapshotRebuilds.REBUILD_SNAPSHOT, RebuildSnapshot::class.java)

    private val commands: Map<String, CommandType> = mapOf(
        "rename" to CommandType.of(ViewStoreContract.View.Commands.RENAME_VIEW, RenameView::class.java),
        "delete" to CommandType.of(Lifecycle.DELETE_AGGREGATE, DeleteAggregate::class.java),
        "transfer-owner" to CommandType.of(Lifecycle.TRANSFER_OWNER, TransferOwner::class.java),
        "set-schedule" to CommandType.of(Schedules.SET_SCHEDULE, SetSchedule::class.java),
        "enable-job" to CommandType.of(ScheduleJobs.ENABLE_JOB, EnableJob::class.java),
        "rebuild" to rebuild(),
        "ledger-retry" to CommandType.of(Ledger.RETRY_PARKED, RetryParked::class.java),
        "ledger-skip" to CommandType.of(Ledger.SKIP_PARKED, SkipParked::class.java),
        "ledger-read" to CommandType.of(Ledger.READ, Any::class.java),
    )

    private fun allowed(): Map<String, Set<String>> = commands.mapValues { (_, command) ->
        callers.filter { (_, caller) ->
            val action = CommandAction.of(
                command,
                ViewStoreContract.View.TYPE.ref("t1", "v-1"),
                caller.scope,
                Creation.NONE
            )
            runBlocking { authorizer.authorize(caller, action) } is Authorization.Allowed
        }.keys
    }

    @Test
    fun `a user is refused the operational commands and keeps its own views', the rest as before`() {
        val owners = setOf("user", "system-writer")
        val operators = setOf("system-writer", "tenant-system-writer")
        allowed().assert().isEqualTo(
            mapOf(
                "rename" to owners,
                "delete" to owners,
                "transfer-owner" to emptySet(),
                "set-schedule" to owners,
                "enable-job" to operators,
                "rebuild" to operators,
                "ledger-retry" to operators,
                "ledger-skip" to operators,
                "ledger-read" to operators,
            ),
        )
    }
}
