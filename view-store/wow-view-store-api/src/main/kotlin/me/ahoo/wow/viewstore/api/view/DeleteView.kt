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
import me.ahoo.wow.api.command.DeleteAggregate
import me.ahoo.wow.api.event.AggregateDeleted

/** Deletes a view (soft): afterwards it reads as not found. Carries the expected version. */
@Order(5)
@Summary("Delete a view")
@CommandRoute(action = "", method = CommandRoute.Method.DELETE, appendIdPath = CommandRoute.AppendPath.ALWAYS)
data class DeleteView(
    @field:CommandRoute.PathVariable
    override val id: String,
) : Identifier, DeleteAggregate

class ViewDeleted : AggregateDeleted {
    override fun equals(other: Any?): Boolean = other is ViewDeleted
    override fun hashCode(): Int = javaClass.hashCode()
}
