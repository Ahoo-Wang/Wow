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
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.viewstore.api.preferences.SetViewPreferences
import me.ahoo.wow.viewstore.api.preferences.ViewPreferencesSet
import me.ahoo.wow.viewstore.domain.ViewApps
import me.ahoo.wow.viewstore.domain.view.ViewConfigs

/**
 * One owner's preferences for one definition (they belong to the tenant and the owner). The service
 * derives their id ([me.ahoo.wow.viewstore.domain.ScopedIds.preferences]) and [SetViewPreferences] creates
 * them on the first write. They are never deleted, so they handle no lifecycle command.
 */
@AggregateRoot(lifecycle = [])
@Description("One owner's view preferences for one definition in one application")
class ViewPreferences(private val state: ViewPreferencesState) {
    @OnCommand(produces = [ViewPreferencesSet::class])
    fun onSet(command: Command<SetViewPreferences>): Decision {
        val appId = if (state.appId.isEmpty()) {
            ViewApps.appOf(command) ?: return ViewApps.appRequired()
        } else {
            ViewApps.otherApp(command, state.appId)?.let { return it }
            state.appId
        }
        val body = command.body
        problemOf(body)?.let { return ViewApps.invalid(it) }
        val set = ViewPreferencesSet(
            definitionId = body.definitionId,
            appId = appId,
            order = body.order,
            defaultInstanceId = body.defaultInstanceId,
            autoRun = body.autoRun,
            lastTabs = body.lastTabs,
        )
        return if (state.definitionId.isNotEmpty() && set == state.toSet()) Decision.NoOp else Decision.events(set)
    }

    /**
     * What is wrong with [body], or null. The ids the preferences hold are indexed whole as exact values, so each is
     * at most [ViewConfigs.MAX_ID_LENGTH] characters, as a view's definition id and panel references are.
     */
    private fun problemOf(body: SetViewPreferences): String? {
        if (body.definitionId.isBlank()) {
            return "The preferences' definitionId must not be blank."
        }
        if (state.definitionId.isNotEmpty() && state.definitionId != body.definitionId) {
            return "The preferences belong to definition [${state.definitionId}]."
        }
        val ids = mapOf("definitionId" to listOf(body.definitionId), "order" to body.order) +
            body.defaultInstanceId?.let { mapOf("defaultInstanceId" to listOf(it)) }.orEmpty()
        return ids.entries.firstOrNull { (_, values) -> values.any { it.length > ViewConfigs.MAX_ID_LENGTH } }?.let {
            "The preferences' ${it.key} holds an id longer than ${ViewConfigs.MAX_ID_LENGTH} characters."
        }
    }

    private fun ViewPreferencesState.toSet() =
        ViewPreferencesSet(definitionId, appId, order, defaultInstanceId, autoRun, lastTabs)
}
