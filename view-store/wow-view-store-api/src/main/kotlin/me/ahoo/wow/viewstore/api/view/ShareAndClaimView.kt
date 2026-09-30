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

import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.api.event.OwnerTransferred
import me.ahoo.wow.viewstore.api.ViewAudience

/**
 * Makes a personal view shared, in place: sent to the view's current personal path
 * (`…/owner/{ownerId}/view/{id}/share`), it moves the view to the owner `(shared)`. The id stays.
 */
@Order(4)
@Summary("Make a view shared")
@CommandRoute(action = "share", method = CommandRoute.Method.PUT, appendIdPath = CommandRoute.AppendPath.ALWAYS)
object ShareView

/**
 * Makes a shared view personal to [toOwnerId], in place. It has no generated route: the view store's route
 * `PUT …/owner/{ownerId}/view/{id}/claim` is sent to the caller's own personal path, takes [toOwnerId] from that
 * path, and dispatches this command to the view's current owner, `(shared)`, since Wow checks a command's owner
 * against the view's. The id stays.
 */
@Summary("Make a shared view personal")
@CommandRoute(enabled = false)
data class ClaimView(
    val toOwnerId: String,
)

data class ViewAudienceChanged(
    val audience: ViewAudience,
    override val toOwnerId: String,
) : OwnerTransferred
