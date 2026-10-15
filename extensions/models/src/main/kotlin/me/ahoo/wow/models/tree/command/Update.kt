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
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.models.tree.Flat
import me.ahoo.wow.models.tree.TreeCoded

/** Updates the node [code] (in the command's body: V10 routes carry the aggregate's id only). */
@Summary("Update tree node")
interface Update<E : Updated> : TreeCoded {
    @get:JsonIgnore
    override val level: Int
        get() = super.level

    /** The event of the update of [previous], the node as it was. */
    fun toEvent(previous: Flat): E
}

/** A node was updated. */
interface Updated : Flat
