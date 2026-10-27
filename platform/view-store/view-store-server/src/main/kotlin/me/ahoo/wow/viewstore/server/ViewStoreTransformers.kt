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

import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.runtime.admission.CommandInput
import me.ahoo.wow.runtime.admission.CommandTarget
import me.ahoo.wow.runtime.admission.CommandTransformer
import me.ahoo.wow.viewstore.api.ViewStoreContract
import me.ahoo.wow.viewstore.api.preferences.SetViewPreferences
import me.ahoo.wow.viewstore.api.wording.ClearDatasetWording
import me.ahoo.wow.viewstore.api.wording.SetDatasetWording
import me.ahoo.wow.viewstore.domain.ScopedIds

/**
 * Finds the preferences and the wording a command is for: their ids are derived from what they are
 * for ([ScopedIds]), as the 9.x preferences and wording routes derived them, so a request names none and one that
 * names an id is sent to the derived one all the same: nobody takes another owner's or application's id. Without
 * the application the command names no id, and the aggregate refuses it (`view-store.app-required`).
 */
class PreferencesTarget : CommandTransformer {
    override val input: CommandType = CommandType.of(
        ViewStoreContract.ViewPreferences.TYPE,
        SetViewPreferences::class.java
    )

    override suspend fun transform(input: CommandInput): CommandTarget {
        val body = input.body as SetViewPreferences
        val id = appOf(
            input
        )?.let { ScopedIds.preferences(input.tenantId, input.caller.scope.ownerId, it, body.definitionId) }
        return CommandTarget.of(input.type, id, body, input.headers)
    }
}

/** Finds the wording a [SetDatasetWording] is for, as [PreferencesTarget] finds preferences. */
class WordingTarget : CommandTransformer {
    override val input: CommandType = CommandType.of(
        ViewStoreContract.DatasetWording.TYPE,
        SetDatasetWording::class.java
    )

    override suspend fun transform(input: CommandInput): CommandTarget {
        val body = input.body as SetDatasetWording
        return CommandTarget.of(
            input.type,
            appOf(input)?.let { ScopedIds.wording(input.tenantId, it, body.dataset) },
            body,
            input.headers
        )
    }
}

/** Finds the wording a [ClearDatasetWording] is for, as [PreferencesTarget] finds preferences. */
class WordingClearTarget : CommandTransformer {
    override val input: CommandType = CommandType.of(
        ViewStoreContract.DatasetWording.TYPE,
        ClearDatasetWording::class.java
    )

    override suspend fun transform(input: CommandInput): CommandTarget {
        val body = input.body as ClearDatasetWording
        return CommandTarget.of(
            input.type,
            appOf(input)?.let { ScopedIds.wording(input.tenantId, it, body.dataset) },
            body,
            input.headers
        )
    }
}

/** The application of [input]: its standard request fact `app_id`, as the aggregate reads it. */
private fun appOf(input: CommandInput): String? = input.headers[RequestFacts.APP_ID]?.trim()?.ifEmpty { null }
