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
import me.ahoo.wow.api.command.DeleteAggregate

/**
 * Deletes a view (soft, as Wow deletes an aggregate): `DELETE …/view/{id}` with the body `{}`, carrying the expected
 * version.
 *
 * A command of the view store's own rather than Wow's `DefaultDeleteAggregate`: an aggregate that handles a command
 * claims that command's type in Wow's metadata, so handling `DefaultDeleteAggregate` here would name its schema after
 * the view store in every host that embeds the starter, and change the host's own delete routes in its OpenAPI.
 */
@Order(5)
@Summary("Delete a view")
@CommandRoute(action = "", method = CommandRoute.Method.DELETE, appendIdPath = CommandRoute.AppendPath.ALWAYS)
object DeleteView : DeleteAggregate
