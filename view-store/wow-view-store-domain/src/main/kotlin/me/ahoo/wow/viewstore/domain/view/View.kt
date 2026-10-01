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
import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DefaultAggregateDeleted
import me.ahoo.wow.api.exception.BindingError
import me.ahoo.wow.viewstore.ViewStoreService.SHARED_OWNER_ID
import me.ahoo.wow.viewstore.api.ScopeIds
import me.ahoo.wow.viewstore.api.ViewAudience
import me.ahoo.wow.viewstore.api.view.ClaimView
import me.ahoo.wow.viewstore.api.view.CreateView
import me.ahoo.wow.viewstore.api.view.DeleteView
import me.ahoo.wow.viewstore.api.view.RenameView
import me.ahoo.wow.viewstore.api.view.SaveView
import me.ahoo.wow.viewstore.api.view.ShareView
import me.ahoo.wow.viewstore.api.view.ViewAudienceChanged
import me.ahoo.wow.viewstore.api.view.ViewCreated
import me.ahoo.wow.viewstore.api.view.ViewRenamed
import me.ahoo.wow.viewstore.api.view.ViewSaved
import me.ahoo.wow.viewstore.domain.ViewApps.requireSameApp
import me.ahoo.wow.viewstore.domain.ViewApps.requiredAppId
import me.ahoo.wow.viewstore.domain.ViewStoreException
import reactor.core.publisher.Mono

/**
 * A saved view. Wow already rejects a command whose owner is not the view's, whose expected version is stale, or
 * that addresses a deleted view; the view rejects one from another application as not found.
 */
@AggregateRoot
@AggregateRoute(owner = AggregateRoute.Owner.ALWAYS)
class View(private val state: ViewState) {

    @OnCommand
    fun onCreate(command: CommandMessage<CreateView>): ViewCreated {
        val appId = command.requiredAppId()
        val body = command.body
        ViewConfigs.requireValid(body.config)
        return ViewCreated(
            definitionId = ViewConfigs.requireDefinitionId(body.definitionId),
            title = ViewConfigs.requireTitle(body.title),
            audience = ViewAudience.ofOwner(command.ownerId),
            appId = appId,
            config = body.config,
        )
    }

    @OnCommand
    fun onSave(command: CommandMessage<SaveView>): ViewSaved {
        command.requireSameApp(state.appId)
        val config = command.body.config
        ViewConfigs.requireValid(config)
        return ViewSaved(config = config)
    }

    @OnCommand
    fun onRename(command: CommandMessage<RenameView>): ViewRenamed {
        command.requireSameApp(state.appId)
        return ViewRenamed(ViewConfigs.requireTitle(command.body.title))
    }

    /**
     * Moves a personal view to the owner `(shared)`. Wow has already checked that the command comes from the view's
     * owner path. A view already shared records the change to the same owner, since every Wow command records an
     * event; the view store's route answers that case without a command.
     */
    @OnCommand
    fun onShare(command: CommandMessage<ShareView>): ViewAudienceChanged {
        command.requireSameApp(state.appId)
        return ViewAudienceChanged(audience = ViewAudience.SHARED, toOwnerId = SHARED_OWNER_ID)
    }

    /**
     * Moves a shared view to the owner named by [ClaimView.toOwnerId], the caller's own path. It is dispatched to
     * the view's current owner, so Wow's owner check passes only for a view that is shared (or, in process, for a
     * personal view claimed by the owner it already has, which records the change to the same owner). A view some
     * shared dashboard references stays shared.
     */
    @OnCommand
    fun onClaim(
        command: CommandMessage<ClaimView>,
        sharedBoardReferences: SharedBoardReferences,
    ): Mono<ViewAudienceChanged> {
        command.requireSameApp(state.appId)
        val toOwnerId = command.body.toOwnerId
        requireClaimable(toOwnerId, command.ownerId)
        if (state.audience == ViewAudience.PERSONAL) {
            return Mono.just(ViewAudienceChanged(audience = ViewAudience.PERSONAL, toOwnerId = toOwnerId))
        }
        return sharedBoardReferences.referencingBoards(command.aggregateId.tenantId, state.appId, state.id)
            .filter { it.id != state.id }
            .collectList()
            .map { boards ->
                if (boards.isNotEmpty()) {
                    throw ViewStoreException.invalid(
                        errorMsg = "The view is referenced by shared dashboards ${boards.map { it.id }}.",
                        bindingErrors = boards.map {
                            BindingError(name = it.id, msg = it.title, code = REFERENCED_BY_SHARED_DASHBOARD)
                        },
                    )
                }
                ViewAudienceChanged(audience = ViewAudience.PERSONAL, toOwnerId = toOwnerId)
            }
    }

    /**
     * The delete (`DELETE …/view/{id}`), in place of Wow's default one, adding the application rule: a view of
     * another application reads as not found. Wow's recover and resource-tags commands are left to Wow; their routes
     * are closed for the view store, so they are in-process only.
     */
    @OnCommand
    fun onDelete(command: CommandMessage<DeleteView>): DefaultAggregateDeleted {
        command.requireSameApp(state.appId)
        return DefaultAggregateDeleted
    }

    /**
     * A view is claimed by a user, never by a reserved owner or an id with anything invisible in it; and a
     * view already personal only by the owner it has
     * (which records the change to the same owner).
     */
    private fun requireClaimable(toOwnerId: String, currentOwnerId: String) {
        if (!toOwnerId.isUserId()) {
            throw ViewStoreException.invalid("A view is claimed by a user, not by the owner [$toOwnerId].")
        }
        if (state.audience == ViewAudience.PERSONAL && toOwnerId != currentOwnerId) {
            throw ViewStoreException.invalid("Only a shared view can be claimed.")
        }
    }

    companion object {
        /** The binding error code naming a shared dashboard that keeps a view shared. */
        const val REFERENCED_BY_SHARED_DASHBOARD = "referenced-by-shared-dashboard"

        /**
         * Whether this can be a user's id: a valid owner of a path ([ScopeIds], so nothing invisible in it), and not
         * in parentheses, which mark a reserved owner such as `(shared)`.
         */
        private fun String.isUserId(): Boolean = ScopeIds.isValid(this) && !(startsWith("(") && endsWith(")"))
    }
}
