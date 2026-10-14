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

package me.ahoo.wow.platform.viewstore.server

import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.platform.viewstore.api.ViewStoreContext
import me.ahoo.wow.platform.viewstore.api.preferences.SetViewPreferences
import me.ahoo.wow.platform.viewstore.api.wording.ClearDatasetWording
import me.ahoo.wow.platform.viewstore.api.wording.SetDatasetWording
import me.ahoo.wow.platform.viewstore.domain.ScopedIds
import me.ahoo.wow.runtime.admission.CommandInput
import me.ahoo.wow.runtime.admission.CommandTarget
import me.ahoo.wow.runtime.admission.CommandTransformer

/**
 * Finds the preferences and the wording a command is for (decision D182): their ids are derived from what they are
 * for ([ScopedIds]), as the 9.x preferences and wording routes derived them, so a request names none and one that
 * names an id is sent to the derived one all the same: nobody takes another owner's or application's id. Without
 * the application the command names no id, and the aggregate refuses it (`view-store.app-required`).
 */
class PreferencesTarget : CommandTransformer {
    override val input: CommandType = command(ViewStoreContext.VIEW_PREFERENCES, SetViewPreferences::class.java)

    override suspend fun transform(input: CommandInput): CommandTarget {
        val body = input.body as SetViewPreferences
        val id = appOf(input)?.let { ScopedIds.preferences(input.caller.scope.ownerId, it, body.definitionId) }
        return CommandTarget(input.type, id, body, input.headers)
    }
}

/** Finds the wording a [SetDatasetWording] is for, as [PreferencesTarget] finds preferences. */
class WordingTarget : CommandTransformer {
    override val input: CommandType = command(ViewStoreContext.DATASET_WORDING, SetDatasetWording::class.java)

    override suspend fun transform(input: CommandInput): CommandTarget {
        val body = input.body as SetDatasetWording
        return CommandTarget(input.type, appOf(input)?.let { ScopedIds.wording(it, body.dataset) }, body, input.headers)
    }
}

/** Finds the wording a [ClearDatasetWording] is for, as [PreferencesTarget] finds preferences. */
class WordingClearTarget : CommandTransformer {
    override val input: CommandType = command(ViewStoreContext.DATASET_WORDING, ClearDatasetWording::class.java)

    override suspend fun transform(input: CommandInput): CommandTarget {
        val body = input.body as ClearDatasetWording
        return CommandTarget(input.type, appOf(input)?.let { ScopedIds.wording(it, body.dataset) }, body, input.headers)
    }
}

/** The application of [input]: its request fact `CoSec-App-Id`, matched without regard to case. */
private fun appOf(input: CommandInput): String? = input.headers.entries
    .firstOrNull { it.key.equals(ViewStoreContext.APP_ID_HEADER, ignoreCase = true) }
    ?.value?.trim()?.ifEmpty { null }

/** The command type of [commandClass] of [aggregate], named after its class (decision D55). */
private fun command(aggregate: String, commandClass: Class<*>): CommandType =
    CommandType("${ViewStoreContext.NAME}.$aggregate.${commandClass.simpleName}", commandClass)
