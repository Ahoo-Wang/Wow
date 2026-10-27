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

import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.viewstore.api.SystemView
import me.ahoo.wow.viewstore.api.ViewAudience
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
import me.ahoo.wow.viewstore.domain.ViewApps
import me.ahoo.wow.viewstore.domain.ViewStoreRejections

/**
 * A saved view. It belongs to its tenant and its owner, which is its audience: the kernel refuses a
 * command whose owner is not the view's, whose expected version is stale, or that addresses a deleted view; the view
 * refuses one from another application as not found.
 *
 * Sharing and claiming move the view to another owner in place: the view decides them and records the new audience,
 * and [ViewAudienceSaga] transfers the owner (`wow.lifecycle.TransferOwner`), the only command that changes it. The
 * service's authorizer keeps `TransferOwner` from HTTP callers, so an owner changes only through these rules.
 *
 * A view of the owner `(system)` is a stored system view: it is global, so it is created under the tenant
 * `(platform)` only, and it never moves audience. Who may write one is the service's authorizer's decision.
 */
@AggregateRoot(lifecycle = [DeleteAggregate::class, TransferOwner::class])
@Description("A saved view of the view engine: a record view, an analysis view or a dashboard")
class View(private val state: ViewState) {
    @OnCommand
    fun onCreate(command: Command<CreateView>): ViewCreated {
        val appId = ViewApps.appOf(command) ?: ViewApps.appRequired()
        val ownerId = command.scope.ownerId ?: ViewApps.reject(
            ViewStoreRejections.OWNER_REQUIRED,
            "A view is saved by an owner: the caller acts as none.",
        )
        val body = command.body
        val problem = ownerProblem(ownerId, command.ref.tenantId)
            ?: idProblem(command.ref.id)
            ?: ViewConfigs.definitionProblem(body.definitionId)
            ?: ViewConfigs.titleProblem(body.title)
            ?: ViewConfigs.problemOf(body.config)
        if (problem != null) {
            ViewApps.invalid(problem)
        }
        return ViewCreated(
            definitionId = body.definitionId,
            title = body.title.trim(),
            kind = ViewConfigs.kindOf(body.config)!!,
            audience = ViewAudience.ofOwner(ownerId),
            appId = appId,
            config = body.config,
        )
    }

    @OnCommand
    fun onSave(command: Command<SaveView>): ViewSaved {
        ViewApps.rejectOtherApp(command, state.appId)
        val config = command.body.config
        ViewConfigs.problemOf(config)?.let(ViewApps::invalid)
        return ViewSaved(ViewConfigs.kindOf(config)!!, config)
    }

    @OnCommand
    fun onRename(command: Command<RenameView>): ViewRenamed? {
        ViewApps.rejectOtherApp(command, state.appId)
        val title = command.body.title
        ViewConfigs.titleProblem(title)?.let(ViewApps::invalid)
        if (title.trim() == state.title) {
            return null
        }
        return ViewRenamed(title.trim())
    }

    /**
     * Makes a personal view shared. A view already shared changes nothing, so its revision does not move: the 9.x
     * route answered that case without a command for the same reason.
     */
    @OnCommand
    fun onShare(command: Command<ShareView>): ViewShared? {
        ViewApps.rejectOtherApp(command, state.appId)
        return when (state.audience) {
            ViewAudience.SYSTEM -> systemReadOnly()
            ViewAudience.SHARED -> null
            ViewAudience.PERSONAL -> ViewShared
        }
    }

    /**
     * Makes a shared view personal to the operator who sends the claim, as the owner `(shared)`: the kernel admits
     * the command only for a view that owner holds. A personal view claimed by its own owner changes nothing; one of
     * anyone else is not claimed. A view some shared dashboard shows stays shared.
     */
    @OnCommand
    suspend fun onClaim(command: Command<ClaimView>, references: SharedBoardReferences): ViewClaimed? {
        ViewApps.rejectOtherApp(command, state.appId)
        if (state.audience == ViewAudience.SYSTEM) {
            systemReadOnly()
        }
        val toOwnerId = command.operator?.id?.takeUnless(ViewStoreContext::isReserved)
            ?: ViewApps.reject(ViewStoreRejections.OWNER_REQUIRED, "A view is claimed by a signed-in user.")
        if (state.audience == ViewAudience.PERSONAL) {
            return if (command.scope.ownerId == toOwnerId) {
                null
            } else {
                ViewApps.invalid("Only a shared view can be claimed.")
            }
        }
        val boards = references.referencingBoards(command.ref.tenantId, state.appId, command.ref.id)
            .filter { it.id != command.ref.id }
        if (boards.isNotEmpty()) {
            ViewApps.reject(
                ViewStoreRejections.REFERENCED_BY_SHARED_DASHBOARD,
                "The view is shown by the shared dashboards ${boards.joinToString { "\"${it.title}\" (${it.id})" }}.",
                mapOf(ViewStoreRejections.BOARDS to boards),
            )
        }
        return ViewClaimed(toOwnerId)
    }

    private fun systemReadOnly(): Nothing = ViewApps.reject(
        ViewStoreRejections.SYSTEM_VIEW_READ_ONLY,
        "A system view never moves audience: it is neither shared nor claimed.",
    )

    /**
     * The prefix `system:` is kept for the system views declared in code: a stored view of that id
     * would not be a system view the service can list, so it is refused whoever creates it.
     */
    private fun idProblem(id: String): String? = if (id.startsWith(SystemView.CODE_SYSTEM_VIEW_PREFIX)) {
        "A view's id [$id] must not start with [${SystemView.CODE_SYSTEM_VIEW_PREFIX}]: the prefix is kept for the " +
            "system views declared in code."
    } else {
        null
    }

    /**
     * Parentheses mark a reserved owner, and only `(shared)` and `(system)` are ones: a view is not created under
     * another (such as `(SYSTEM)`, which would read as a user's). A system view is global, so it is created under the
     * tenant `(platform)` only.
     */
    private fun ownerProblem(ownerId: String, tenantId: String): String? = when {
        ViewStoreContext.isReserved(ownerId) &&
            ownerId != ViewStoreContext.SHARED_OWNER_ID &&
            ownerId != ViewStoreContext.SYSTEM_OWNER_ID ->
            "A view is not created under the reserved owner [$ownerId]."

        ownerId == ViewStoreContext.SYSTEM_OWNER_ID && tenantId != ViewStoreContext.SYSTEM_TENANT_ID ->
            "A system view is global: it is created under the tenant [${ViewStoreContext.SYSTEM_TENANT_ID}] only, " +
                "not [$tenantId]."

        else -> null
    }
}
