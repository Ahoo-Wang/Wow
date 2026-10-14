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

package me.ahoo.wow.platform.viewstore.domain.view

import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.platform.viewstore.api.ViewAudience
import me.ahoo.wow.platform.viewstore.api.ViewStoreContext
import me.ahoo.wow.platform.viewstore.api.view.ClaimView
import me.ahoo.wow.platform.viewstore.api.view.CreateView
import me.ahoo.wow.platform.viewstore.api.view.RenameView
import me.ahoo.wow.platform.viewstore.api.view.SaveView
import me.ahoo.wow.platform.viewstore.api.view.ShareView
import me.ahoo.wow.platform.viewstore.api.view.ViewClaimed
import me.ahoo.wow.platform.viewstore.api.view.ViewCreated
import me.ahoo.wow.platform.viewstore.api.view.ViewRenamed
import me.ahoo.wow.platform.viewstore.api.view.ViewSaved
import me.ahoo.wow.platform.viewstore.api.view.ViewShared
import me.ahoo.wow.platform.viewstore.domain.ViewApps
import me.ahoo.wow.platform.viewstore.domain.ViewStoreRejections

/**
 * A saved view. It belongs to its tenant and its owner (decision D189), which is its audience: the kernel refuses a
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
    @OnCommand(produces = [ViewCreated::class])
    fun onCreate(command: Command<CreateView>): Decision {
        val appId = ViewApps.appOf(command) ?: return ViewApps.appRequired()
        val ownerId = command.scope.ownerId ?: return Decision.reject(
            ViewStoreRejections.OWNER_REQUIRED,
            "A view is saved by an owner: the caller acts as none.",
        )
        val body = command.body
        val problem = ownerProblem(ownerId, command.ref.tenantId)
            ?: ViewConfigs.definitionProblem(body.definitionId)
            ?: ViewConfigs.titleProblem(body.title)
            ?: ViewConfigs.problemOf(body.config)
        if (problem != null) {
            return ViewApps.invalid(problem)
        }
        return Decision.events(
            ViewCreated(
                definitionId = body.definitionId,
                title = body.title.trim(),
                kind = ViewConfigs.kindOf(body.config)!!,
                audience = ViewAudience.ofOwner(ownerId),
                appId = appId,
                config = body.config,
            ),
        )
    }

    @OnCommand(produces = [ViewSaved::class])
    fun onSave(command: Command<SaveView>): Decision {
        ViewApps.otherApp(command, state.appId)?.let { return it }
        val config = command.body.config
        ViewConfigs.problemOf(config)?.let { return ViewApps.invalid(it) }
        return Decision.events(ViewSaved(ViewConfigs.kindOf(config)!!, config))
    }

    @OnCommand(produces = [ViewRenamed::class])
    fun onRename(command: Command<RenameView>): Decision {
        ViewApps.otherApp(command, state.appId)?.let { return it }
        val title = command.body.title
        ViewConfigs.titleProblem(title)?.let { return ViewApps.invalid(it) }
        if (title.trim() == state.title) {
            return Decision.NoOp
        }
        return Decision.events(ViewRenamed(title.trim()))
    }

    /**
     * Makes a personal view shared. A view already shared changes nothing, so its revision does not move: the 9.x
     * route answered that case without a command for the same reason.
     */
    @OnCommand(produces = [ViewShared::class])
    fun onShare(command: Command<ShareView>): Decision {
        ViewApps.otherApp(command, state.appId)?.let { return it }
        return when (state.audience) {
            ViewAudience.SYSTEM -> systemReadOnly()
            ViewAudience.SHARED -> Decision.NoOp
            ViewAudience.PERSONAL -> Decision.events(ViewShared)
        }
    }

    /**
     * Makes a shared view personal to the operator who sends the claim, as the owner `(shared)`: the kernel admits
     * the command only for a view that owner holds. A personal view claimed by its own owner changes nothing; one of
     * anyone else is not claimed. A view some shared dashboard shows stays shared.
     */
    @OnCommand(produces = [ViewClaimed::class])
    suspend fun onClaim(command: Command<ClaimView>, references: SharedBoardReferences): Decision {
        ViewApps.otherApp(command, state.appId)?.let { return it }
        if (state.audience == ViewAudience.SYSTEM) {
            return systemReadOnly()
        }
        val toOwnerId = command.operator?.id?.takeUnless(ViewStoreContext::isReserved)
            ?: return Decision.reject(ViewStoreRejections.OWNER_REQUIRED, "A view is claimed by a signed-in user.")
        if (state.audience == ViewAudience.PERSONAL) {
            return if (command.scope.ownerId == toOwnerId) {
                Decision.NoOp
            } else {
                ViewApps.invalid("Only a shared view can be claimed.")
            }
        }
        val boards = references.referencingBoards(command.ref.tenantId, state.appId, command.ref.id)
            .filter { it.id != command.ref.id }
        if (boards.isNotEmpty()) {
            return Decision.reject(
                ViewStoreRejections.REFERENCED_BY_SHARED_DASHBOARD,
                "The view is shown by the shared dashboards ${boards.joinToString { "\"${it.title}\" (${it.id})" }}.",
                mapOf(ViewStoreRejections.BOARDS to boards),
            )
        }
        return Decision.events(ViewClaimed(toOwnerId))
    }

    private fun systemReadOnly(): Decision = Decision.reject(
        ViewStoreRejections.SYSTEM_VIEW_READ_ONLY,
        "A system view never moves audience: it is neither shared nor claimed.",
    )

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
