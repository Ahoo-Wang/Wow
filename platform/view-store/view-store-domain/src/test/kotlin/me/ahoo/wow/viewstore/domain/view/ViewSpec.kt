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

package me.ahoo.wow.viewstore.domain.view

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.lifecycle.AggregateDeleted
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.lifecycle.OwnerTransferred
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.test.AggregateSpec
import me.ahoo.wow.test.aggregateSpec
import me.ahoo.wow.viewstore.api.ViewAudience
import me.ahoo.wow.viewstore.api.ViewKind
import me.ahoo.wow.viewstore.api.ViewStoreContext
import me.ahoo.wow.viewstore.api.view.ClaimView
import me.ahoo.wow.viewstore.api.view.CreateView
import me.ahoo.wow.viewstore.api.view.RenameView
import me.ahoo.wow.viewstore.api.view.SaveView
import me.ahoo.wow.viewstore.api.view.ShareView
import me.ahoo.wow.viewstore.api.view.ViewClaimed
import me.ahoo.wow.viewstore.api.view.ViewCreated
import me.ahoo.wow.viewstore.api.view.ViewRenamed
import me.ahoo.wow.viewstore.api.view.ViewSaved
import me.ahoo.wow.viewstore.api.view.ViewShared
import me.ahoo.wow.viewstore.domain.ViewFixtures.ALICE
import me.ahoo.wow.viewstore.domain.ViewFixtures.APP
import me.ahoo.wow.viewstore.domain.ViewFixtures.BOB
import me.ahoo.wow.viewstore.domain.ViewFixtures.OTHER_APP
import me.ahoo.wow.viewstore.domain.ViewFixtures.SAGA
import me.ahoo.wow.viewstore.domain.ViewFixtures.SHARED
import me.ahoo.wow.viewstore.domain.ViewFixtures.SYSTEM
import me.ahoo.wow.viewstore.domain.ViewFixtures.TENANT
import me.ahoo.wow.viewstore.domain.ViewFixtures.crossing
import me.ahoo.wow.viewstore.domain.ViewFixtures.dashboardConfig
import me.ahoo.wow.viewstore.domain.ViewFixtures.nobody
import me.ahoo.wow.viewstore.domain.ViewFixtures.owner
import me.ahoo.wow.viewstore.domain.ViewFixtures.recordConfig
import me.ahoo.wow.viewstore.domain.ViewFixtures.reserved
import me.ahoo.wow.viewstore.domain.ViewFixtures.user
import me.ahoo.wow.viewstore.domain.ViewStoreRejections
import org.junit.jupiter.api.Test

/**
 * The saved view on the kernel: the 9.x `ViewSpec`'s scenarios, ported (the owner is the command's scope, the
 * application its request fact, the claiming user its operator), and the kernel's guards that replace the 9.x owner
 * policy.
 */
class ViewSpec {
    private val boards = mutableListOf<BoardReference>()
    private val asked = mutableListOf<Triple<String, String, String>>()
    private val references = SharedBoardReferences { tenantId, appId, viewId ->
        asked += Triple(tenantId, appId, viewId)
        boards.toList()
    }

    /** The view [ID] in [tenantId], Alice's commands in her application by default. */
    private fun viewSpec(
        tenantId: String = TENANT,
        caller: Caller = user(ALICE),
        block: AggregateSpec<ViewState>.() -> Unit,
    ) = aggregateSpec(
        viewModel(sharedBoardReferences = references),
        id = ID,
        caller = caller,
        tenantId = tenantId,
        block = block,
    )

    /** The view, created by no user in [ownerId]'s scope from the application, or by [caller] in [scope]. */
    private fun AggregateSpec<ViewState>.created(
        ownerId: String,
        caller: Caller = nobody(owner(ownerId)),
        scope: Scope? = null,
    ) {
        givenCommands(CreateView("orders", "Orders", recordConfig()), caller = caller, scope = scope)
    }

    @Test
    fun `a personal view should be created, saved and renamed by its owner in its application`() = viewSpec {
        whenCommand(CreateView("orders", "  Open orders  ", recordConfig())) {
            expectEvents(
                ViewCreated("orders", "Open orders", ViewKind.RECORD, ViewAudience.PERSONAL, APP, recordConfig()),
            )
            expectScope(owner(ALICE))
            expectState {
                definitionId.assert().isEqualTo("orders")
                title.assert().isEqualTo("Open orders")
                audience.assert().isEqualTo(ViewAudience.PERSONAL)
                appId.assert().isEqualTo(APP)
                kind.assert().isEqualTo(ViewKind.RECORD)
            }
        }
        val board = dashboardConfig("v1")
        whenCommand(SaveView(board)) {
            expectEvents(ViewSaved(ViewKind.DASHBOARD, board))
            expectState {
                kind.assert().isEqualTo(ViewKind.DASHBOARD)
                config.assert().isEqualTo(board)
            }
        }
        whenCommand(SaveView(mapOf("kind" to "chart"))) {
            expectRejected(ViewStoreRejections.INVALID)
        }
        whenCommand(RenameView(" Closed orders ")) {
            expectEvents(ViewRenamed("Closed orders"))
        }
        whenCommand(RenameView("Closed orders")) { expectUnchanged() }
        whenCommand(RenameView("   ")) {
            expectRejected(ViewStoreRejections.INVALID)
        }
    }

    @Test
    fun `another application, another owner or no application should not reach a view`() = viewSpec {
        created(ALICE)
        listOf(SaveView(recordConfig()), RenameView("Mine"), ShareView).forEach { command ->
            whenCommand(command, caller = user(ALICE, app = OTHER_APP)) {
                expectRejected(ViewStoreRejections.NOT_FOUND)
            }
            whenCommand(command, caller = user(ALICE, app = null)) { expectRejected(ViewStoreRejections.APP_REQUIRED) }
            whenCommand(command, caller = user(BOB)) { expectRejected(FrameworkCodes.AGGREGATE_NOT_FOUND) }
        }
        whenCommand(ClaimView, caller = user(ALICE, app = OTHER_APP)) {
            expectRejected(ViewStoreRejections.NOT_FOUND)
        }
        whenCommand(ClaimView, caller = user(ALICE, app = null)) {
            expectRejected(ViewStoreRejections.APP_REQUIRED)
        }
    }

    @Test
    fun `a view should be created by an owner with an application and a valid config`() = viewSpec {
        whenCommand(CreateView("orders", "Open orders", recordConfig()), caller = user(ALICE, app = null)) {
            expectRejected(ViewStoreRejections.APP_REQUIRED)
        }
        whenCommand(CreateView("orders", "Open orders", recordConfig()), caller = nobody()) {
            expectRejected(ViewStoreRejections.OWNER_REQUIRED)
        }
        whenCommand(CreateView("orders", " ", recordConfig())) {
            expectRejected(ViewStoreRejections.INVALID) { message.assert().contains("title") }
        }
        whenCommand(CreateView(" ", "Open orders", recordConfig())) {
            expectRejected(ViewStoreRejections.INVALID) { message.assert().contains("definitionId") }
        }
        whenCommand(CreateView("orders", "Open", mapOf("kind" to "x"))) {
            expectRejected(ViewStoreRejections.INVALID) { message.assert().contains("kind") }
        }
        whenCommand(CreateView("orders", "Open", recordConfig()), caller = nobody(owner("(SYSTEM)"))) {
            expectRejected(ViewStoreRejections.INVALID) { message.assert().contains("reserved") }
        }
        whenCommand(CreateView("orders", "Open", recordConfig()), caller = nobody(SYSTEM)) {
            expectRejected(ViewStoreRejections.INVALID) { message.assert().contains("global") }
        }
    }

    @Test
    fun `a view should not take an id of the system views declared in code (R2_3-7)`() = aggregateSpec(
        viewModel(sharedBoardReferences = references),
        id = "system:x",
        caller = user(ALICE),
    ) {
        whenCommand(CreateView("orders", "Open", recordConfig())) {
            expectRejected(ViewStoreRejections.INVALID) { message.assert().contains("system:") }
        }
    }

    @Test
    fun `a personal view should be shared once, and its owner moved by the saga`() = viewSpec {
        created(ALICE)
        whenCommand(ShareView) {
            expectEvents(ViewShared)
            expectState { audience.assert().isEqualTo(ViewAudience.SHARED) }
        }
        // The saga's TransferOwner, derived from the share and not checked against the target's scope (D199).
        whenCommand(TransferOwner.of(ViewStoreContext.SHARED_OWNER_ID), caller = SAGA) {
            expectEvents(OwnerTransferred.of(ViewStoreContext.SHARED_OWNER_ID))
            expectScope(SHARED)
        }
        whenCommand(ShareView, caller = nobody(SHARED)) { expectUnchanged() }
        whenCommand(ShareView) { expectRejected(FrameworkCodes.AGGREGATE_NOT_FOUND) }
    }

    @Test
    fun `a shared view should be claimed by the user who sends the claim`() = viewSpec {
        created(ViewStoreContext.SHARED_OWNER_ID)
        whenCommand(ClaimView, caller = reserved(user(BOB), SHARED), scope = SHARED) {
            expectEvents(ViewClaimed(BOB))
            expectState { audience.assert().isEqualTo(ViewAudience.PERSONAL) }
        }
        asked.assert().containsExactly(Triple(TENANT, APP, ID))
        whenCommand(TransferOwner.of(BOB), caller = SAGA) { expectScope(owner(BOB)) }
        // Claimed again by its owner: nothing changes; by anyone else as themselves, it is out of their reach.
        whenCommand(ClaimView, caller = user(BOB)) { expectUnchanged() }
        whenCommand(ClaimView) {
            expectRejected(FrameworkCodes.AGGREGATE_NOT_FOUND)
        }
    }

    @Test
    fun `a personal view should not be claimed for another user, nor by nobody`() = viewSpec {
        created(ALICE)
        // Only a caller that crosses scopes names another owner's (a user who does is forbidden at admission).
        whenCommand(ClaimView, caller = crossing(BOB), scope = owner(ALICE)) {
            expectRejected(ViewStoreRejections.INVALID) { message.assert().contains("shared") }
        }
        whenCommand(ClaimView, caller = nobody(owner(ALICE))) {
            expectRejected(ViewStoreRejections.OWNER_REQUIRED)
        }
        whenCommand(ClaimView, caller = crossing("(shared)"), scope = owner(ALICE)) {
            expectRejected(ViewStoreRejections.OWNER_REQUIRED)
        }
    }

    @Test
    fun `a shared view a shared dashboard shows should stay shared`() = viewSpec {
        created(ViewStoreContext.SHARED_OWNER_ID)
        boards += BoardReference(ID, "Itself")
        boards += BoardReference("board-1", "Weekly board")
        whenCommand(ClaimView, caller = reserved(user(BOB), SHARED), scope = SHARED) {
            expectRejected(ViewStoreRejections.REFERENCED_BY_SHARED_DASHBOARD) {
                message.assert().contains("Weekly board").contains("board-1").doesNotContain("Itself")
                extensions.assert().isEqualTo(
                    mapOf(ViewStoreRejections.BOARDS to listOf(BoardReference("board-1", "Weekly board"))),
                )
            }
            expectState { audience.assert().isEqualTo(ViewAudience.SHARED) }
        }
        boards.removeAt(1)
        whenCommand(ClaimView, caller = reserved(user(BOB), SHARED), scope = SHARED) {
            expectEvents(ViewClaimed(BOB))
        }
    }

    @Test
    fun `a stored system view should be global and never move audience`() = viewSpec(
        ViewStoreContext.SYSTEM_TENANT_ID,
        caller = reserved(nobody(), SYSTEM),
    ) {
        // Every command names the tenant `(platform)` through its reserved scope, as the server's requests do.
        created(ViewStoreContext.SYSTEM_OWNER_ID, caller = reserved(nobody(), SYSTEM), scope = SYSTEM)
        whenCommand(SaveView(dashboardConfig("v1")), scope = SYSTEM) {
            expectEventTypes(ViewSaved::class)
            expectState { audience.assert().isEqualTo(ViewAudience.SYSTEM) }
        }
        whenCommand(ShareView, scope = SYSTEM) {
            expectRejected(ViewStoreRejections.SYSTEM_VIEW_READ_ONLY)
        }
        whenCommand(ClaimView, caller = reserved(user(BOB), SYSTEM), scope = SYSTEM) {
            expectRejected(ViewStoreRejections.SYSTEM_VIEW_READ_ONLY)
        }
        // An interim answer until #6556, which production never gives: the view type's tenant is fixed to `(platform)`,
        // so there Alice's command lands in that tenant and is answered AGGREGATE_NOT_FOUND. The test DSL does not yet
        // apply a type's fixed tenant, so it refuses her ref's tenant as malformed. #6556 restores AGGREGATE_NOT_FOUND.
        assertThrownBy<IllegalArgumentException> {
            whenCommand(SaveView(recordConfig()), caller = user(ALICE)) { }
        }.hasMessageContaining(FrameworkCodes.COMMAND_MALFORMED)
        whenCommand(DeleteAggregate(), caller = reserved(nobody(app = null), SYSTEM), scope = SYSTEM) {
            expectEvents(AggregateDeleted())
        }
    }

    @Test
    fun `a deleted view should take no command`() = viewSpec {
        created(ALICE)
        whenCommand(DeleteAggregate(), caller = user(ALICE, app = null)) { expectEvents(AggregateDeleted()) }
        whenCommand(RenameView("Again")) {
            expectRejected(FrameworkCodes.AGGREGATE_DELETED)
        }
    }

    private companion object {
        const val ID = "view-1"
    }
}
