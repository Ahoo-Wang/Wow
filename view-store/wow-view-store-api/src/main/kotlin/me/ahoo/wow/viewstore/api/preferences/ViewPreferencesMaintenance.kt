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

package me.ahoo.wow.viewstore.api.preferences

import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.abac.AbacTags
import me.ahoo.wow.api.abac.ApplyResourceTags
import me.ahoo.wow.api.abac.ResourceTagsApplied
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.command.DeleteAggregate
import me.ahoo.wow.api.command.RecoverAggregate
import me.ahoo.wow.api.event.AggregateDeleted
import me.ahoo.wow.api.event.AggregateRecovered

/*
 * Wow gives every aggregate a delete, a recover and a tags command of its own, routed and blind to the application.
 * The preferences declare their own instead, with no route and the application rule of setting them, so the only
 * route to preferences is the view store's.
 */

/** Deletes preferences; in-process only. */
@CommandRoute(enabled = false)
data class DeleteViewPreferences(
    @field:CommandRoute.PathVariable
    override val id: String,
) : Identifier, DeleteAggregate

class ViewPreferencesDeleted : AggregateDeleted {
    override fun equals(other: Any?): Boolean = other is ViewPreferencesDeleted
    override fun hashCode(): Int = javaClass.hashCode()
}

/** Recovers deleted preferences; in-process only. */
@CommandRoute(enabled = false)
data class RecoverViewPreferences(
    @field:CommandRoute.PathVariable
    override val id: String,
) : Identifier, RecoverAggregate

class ViewPreferencesRecovered : AggregateRecovered {
    override fun equals(other: Any?): Boolean = other is ViewPreferencesRecovered
    override fun hashCode(): Int = javaClass.hashCode()
}

/** Applies ABAC resource tags to preferences; in-process only. */
@CommandRoute(enabled = false)
data class ApplyViewPreferencesTags(
    @field:CommandRoute.PathVariable
    override val id: String,
    override val tags: AbacTags,
) : Identifier, ApplyResourceTags

data class ViewPreferencesTagsApplied(override val tags: AbacTags) : ResourceTagsApplied
