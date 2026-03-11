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

package me.ahoo.wow.api.abac

import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.Event
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.api.command.validation.CommandValidator

@Summary("Apply ABAC Tags")
interface ApplyAbacTags : AbacTaggable, CommandValidator {
    override fun validate() {
        require(!tags.keys.any { it.isBlank() }) {
            "Tags can not contain blank keys!"
        }
    }
}

@CommandRoute(action = "tags", method = CommandRoute.Method.PUT, appendIdPath = CommandRoute.AppendPath.ALWAYS)
data class DefaultApplyAbacTags(
    override val tags: AbacTags
) : ApplyAbacTags

@Event
interface AbacTagsApplied : AbacTaggable

data class DefaultAbacTagsApplied(
    override val tags: AbacTags
) : AbacTagsApplied
