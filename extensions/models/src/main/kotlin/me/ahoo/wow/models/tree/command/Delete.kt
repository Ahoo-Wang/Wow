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

package me.ahoo.wow.models.tree.command

import com.fasterxml.jackson.annotation.JsonIgnore
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.models.tree.Flat
import me.ahoo.wow.models.tree.TreeCoded

/**
 * Deletes the node [code], which has no children. 9.x took the code from the route's path (`DELETE .../{code}`); a
 * V10 route carries the aggregate's id only, so the code is in the command's body.
 */
@Summary("Delete tree node")
@Description("Id is the tenant ID.")
interface Delete<E : Deleted> : TreeCoded {
    @get:JsonIgnore
    override val level: Int
        get() = super.level

    /** The event of the deletion of [previous], the node as it was. */
    fun toEvent(previous: Flat): E
}

/** A node was deleted. */
interface Deleted : TreeCoded
