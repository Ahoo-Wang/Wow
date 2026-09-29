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
import me.ahoo.wow.api.exception.BindingError
import me.ahoo.wow.command.CommandOperator.operator
import me.ahoo.wow.viewstore.ViewStoreService.SHARED_OWNER_ID
import me.ahoo.wow.viewstore.api.ViewAudience
import me.ahoo.wow.viewstore.api.view.ApplyViewTags
import me.ahoo.wow.viewstore.api.view.ChangeViewAudience
import me.ahoo.wow.viewstore.api.view.CreateView
import me.ahoo.wow.viewstore.api.view.DeleteView
import me.ahoo.wow.viewstore.api.view.RecoverView
import me.ahoo.wow.viewstore.api.view.RenameView
import me.ahoo.wow.viewstore.api.view.SaveView
import me.ahoo.wow.viewstore.api.view.ViewAudienceChanged
import me.ahoo.wow.viewstore.api.view.ViewCreated
import me.ahoo.wow.viewstore.api.view.ViewDeleted
import me.ahoo.wow.viewstore.api.view.ViewRecovered
import me.ahoo.wow.viewstore.api.view.ViewRenamed
import me.ahoo.wow.viewstore.api.view.ViewSaved
import me.ahoo.wow.viewstore.api.view.ViewTagsApplied
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
        val kind = ViewConfigs.requireValid(body.config)
        return ViewCreated(
            definitionId = ViewConfigs.requireDefinitionId(body.definitionId),
            title = ViewConfigs.requireTitle(body.title),
            audience = ViewAudience.ofOwner(command.ownerId),
            appId = appId,
            kind = kind,
            config = body.config,
            references = ViewConfigs.references(kind, body.config),
        )
    }

    @OnCommand
    fun onSave(command: CommandMessage<SaveView>): ViewSaved {
        command.requireSameApp(state.appId)
        val config = command.body.config
        val kind = ViewConfigs.requireValid(config)
        return ViewSaved(kind = kind, config = config, references = ViewConfigs.references(kind, config))
    }

    @OnCommand
    fun onRename(command: CommandMessage<RenameView>): ViewRenamed {
        command.requireSameApp(state.appId)
        return ViewRenamed(ViewConfigs.requireTitle(command.body.title))
    }

    /**
     * The server picks the new owner: `(shared)` for shared, the operator for personal. A view some shared dashboard
     * references stays shared. An audience that does not change still records the change, since every Wow command
     * records an event: the owner and the state stay as they were.
     */
    @OnCommand
    fun onChangeAudience(
        command: CommandMessage<ChangeViewAudience>,
        sharedBoardReferences: SharedBoardReferences,
    ): Mono<ViewAudienceChanged> {
        command.requireSameApp(state.appId)
        val audience = command.body.audience
        if (audience == state.audience) {
            return Mono.just(ViewAudienceChanged(audience = audience, toOwnerId = command.ownerId))
        }
        if (audience == ViewAudience.SHARED) {
            return Mono.just(ViewAudienceChanged(audience = audience, toOwnerId = SHARED_OWNER_ID))
        }
        val operator = command.header.operator
        if (operator.isNullOrBlank() || operator == SHARED_OWNER_ID) {
            throw ViewStoreException.operatorRequired()
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
                ViewAudienceChanged(audience = audience, toOwnerId = operator)
            }
    }

    @OnCommand
    fun onDelete(command: CommandMessage<DeleteView>): ViewDeleted {
        command.requireSameApp(state.appId)
        return ViewDeleted()
    }

    @OnCommand
    fun onRecover(command: CommandMessage<RecoverView>): ViewRecovered {
        command.requireSameApp(state.appId)
        return ViewRecovered()
    }

    @OnCommand
    fun onApplyTags(command: CommandMessage<ApplyViewTags>): ViewTagsApplied {
        command.requireSameApp(state.appId)
        return ViewTagsApplied(command.body.tags)
    }

    companion object {
        /** The binding error code naming a shared dashboard that keeps a view shared. */
        const val REFERENCED_BY_SHARED_DASHBOARD = "referenced-by-shared-dashboard"
    }
}
