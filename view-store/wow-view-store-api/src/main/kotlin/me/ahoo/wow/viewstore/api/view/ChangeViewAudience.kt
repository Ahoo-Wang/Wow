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

package me.ahoo.wow.viewstore.api.view

import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.api.event.OwnerTransferred
import me.ahoo.wow.viewstore.api.ViewAudience

/**
 * Makes a view shared or personal, in place: it is sent to the view's current owner path and transfers the owner.
 * The server picks the new owner: `(shared)` for shared, the command's operator for personal. The id stays.
 */
@Order(4)
@Summary("Make a view shared or personal")
@CommandRoute(action = "audience", method = CommandRoute.Method.PUT, appendIdPath = CommandRoute.AppendPath.ALWAYS)
data class ChangeViewAudience(
    @field:CommandRoute.PathVariable
    override val id: String,
    val audience: ViewAudience,
) : Identifier

data class ViewAudienceChanged(
    val audience: ViewAudience,
    override val toOwnerId: String,
) : OwnerTransferred
