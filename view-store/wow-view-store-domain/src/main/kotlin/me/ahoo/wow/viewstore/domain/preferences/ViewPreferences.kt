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

package me.ahoo.wow.viewstore.domain.preferences

import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.viewstore.api.preferences.ApplyViewPreferencesTags
import me.ahoo.wow.viewstore.api.preferences.DeleteViewPreferences
import me.ahoo.wow.viewstore.api.preferences.RecoverViewPreferences
import me.ahoo.wow.viewstore.api.preferences.SetViewPreferences
import me.ahoo.wow.viewstore.api.preferences.ViewPreferencesDeleted
import me.ahoo.wow.viewstore.api.preferences.ViewPreferencesRecovered
import me.ahoo.wow.viewstore.api.preferences.ViewPreferencesSet
import me.ahoo.wow.viewstore.api.preferences.ViewPreferencesTagsApplied
import me.ahoo.wow.viewstore.domain.ViewApps.requireSameApp
import me.ahoo.wow.viewstore.domain.ViewApps.requiredAppId
import me.ahoo.wow.viewstore.domain.ViewStoreException

/**
 * One owner's preferences for one definition. The preferences route derives its id and dispatches
 * [SetViewPreferences], which creates it on the first write (expected version `0`).
 */
@AggregateRoot
@AggregateRoute(owner = AggregateRoute.Owner.ALWAYS)
class ViewPreferences(private val state: ViewPreferencesState) {

    @OnCommand
    fun onSet(command: CommandMessage<SetViewPreferences>): ViewPreferencesSet {
        val body = command.body
        val appId = if (state.appId.isEmpty()) {
            command.requiredAppId()
        } else {
            command.requireSameApp(state.appId)
            state.appId
        }
        if (body.definitionId.isBlank()) {
            throw ViewStoreException.invalid("The preferences' definitionId must not be blank.")
        }
        if (state.definitionId.isNotEmpty() && state.definitionId != body.definitionId) {
            throw ViewStoreException.invalid("The preferences belong to definition [${state.definitionId}].")
        }
        return ViewPreferencesSet(
            definitionId = body.definitionId,
            appId = appId,
            order = body.order,
            defaultInstanceId = body.defaultInstanceId,
            autoRun = body.autoRun,
            lastTabs = body.lastTabs,
        )
    }

    @OnCommand
    fun onDelete(command: CommandMessage<DeleteViewPreferences>): ViewPreferencesDeleted {
        command.requireSameApp(state.appId)
        return ViewPreferencesDeleted()
    }

    @OnCommand
    fun onRecover(command: CommandMessage<RecoverViewPreferences>): ViewPreferencesRecovered {
        command.requireSameApp(state.appId)
        return ViewPreferencesRecovered()
    }

    @OnCommand
    fun onApplyTags(command: CommandMessage<ApplyViewPreferencesTags>): ViewPreferencesTagsApplied {
        command.requireSameApp(state.appId)
        return ViewPreferencesTagsApplied(command.body.tags)
    }
}
