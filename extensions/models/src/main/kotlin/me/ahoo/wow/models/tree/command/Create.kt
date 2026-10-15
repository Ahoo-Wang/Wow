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

import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.models.common.NotBlankNameCapable
import me.ahoo.wow.models.tree.Flat

/**
 * Creates a node under [parentCode] ([me.ahoo.wow.models.tree.ROOT_CODE] for the first level); the tree gives it its
 * code and its place after its siblings. The aggregate is the tree (its id typically the tenant's), so the command
 * also creates it: annotate the concrete command `@AllowCreate` too, as the compiler reads the command's own class.
 */
@Summary("Create tree node")
@Description("Id is the tenant ID.")
@AllowCreate
interface Create<E : Created> : NotBlankNameCapable {
    val parentCode: String

    /** The event of the node created with [code] at [sortId]. */
    fun toEvent(code: String, sortId: Int): E
}

/** A node was created. */
interface Created : Flat
