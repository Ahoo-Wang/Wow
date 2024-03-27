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

package me.ahoo.wow.models.tree.aggregate

import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.models.tree.ROOT_CODE
import me.ahoo.wow.models.tree.childCodePrefix
import me.ahoo.wow.models.tree.command.Create
import me.ahoo.wow.models.tree.command.Created
import me.ahoo.wow.models.tree.command.Delete
import me.ahoo.wow.models.tree.command.Deleted
import me.ahoo.wow.models.tree.command.Move
import me.ahoo.wow.models.tree.command.Moved
import me.ahoo.wow.models.tree.command.Update
import me.ahoo.wow.models.tree.command.Updated
import me.ahoo.wow.models.tree.treeCode

abstract class Tree<T : TreeState<*, *, *, *, *>, C : Create, U : Update, D : Delete, M : Move>(private val state: T) {

    abstract fun generateCode(): String

    abstract fun maxLevel(): Int

    @OnCommand
    fun onCreate(command: C): Created {
        var code: String = generateCode()
        var sortId = 0

        if (command.parentCode != ROOT_CODE) {
            require(state.children.any { it.code == command.parentCode }) {
                "Parent node not found. parentCode:${command.parentCode}"
            }

            code = treeCode(command.parentCode, code)
            val childCodePrefix = childCodePrefix(command.parentCode)
            state.children.filter { it.code.startsWith(childCodePrefix) }
                .maxOfOrNull { it.sortId }?.let {
                    sortId = it + 1
                }
        }

        val event = command.toEvent<Created>(code = code, sortId = sortId)

        require(event.level <= maxLevel()) {
            "Tree node level exceeds the maximum level. level:${event.level} maxLevel:${maxLevel()}"
        }
        return event
    }

    @OnCommand
    fun onDelete(command: D): Deleted {
        val node = state.children.firstOrNull { it.code == command.code }
        requireNotNull(node) {
            "Tree node not found. code:${command.code}"
        }
        val childCodePrefix = childCodePrefix(command.code)
        val hasChild = state.children.any {
            it.code.startsWith(childCodePrefix)
        }
        require(!hasChild) {
            "Tree node has children. code:${command.code}"
        }
        return command.toEvent()
    }

    @OnCommand
    fun onUpdate(command: U): Updated {
        require(state.children.any { it.code == command.code }) {
            "Tree node not found. code:${command.code}"
        }
        return command.toEvent()
    }

    @OnCommand
    fun onMove(command: M): Moved {
        return command.toEvent()
    }
}
