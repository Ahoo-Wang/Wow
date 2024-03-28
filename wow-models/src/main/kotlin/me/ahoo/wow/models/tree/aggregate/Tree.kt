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
import me.ahoo.wow.models.tree.TreeCoded
import me.ahoo.wow.models.tree.TreeCoded.Companion.childCodePrefix
import me.ahoo.wow.models.tree.TreeCoded.Companion.isDirectChild
import me.ahoo.wow.models.tree.TreeCoded.Companion.treeCode
import me.ahoo.wow.models.tree.command.Create
import me.ahoo.wow.models.tree.command.Created
import me.ahoo.wow.models.tree.command.Delete
import me.ahoo.wow.models.tree.command.Deleted
import me.ahoo.wow.models.tree.command.Move
import me.ahoo.wow.models.tree.command.Moved
import me.ahoo.wow.models.tree.command.Update
import me.ahoo.wow.models.tree.command.Updated

abstract class Tree<T : TreeState<*, *, *, *, *>, C : Create<*>, U : Update<*>, D : Delete<*>, M : Move<*>>(private val state: T) {

    abstract fun generateCode(): String

    abstract fun maxLevel(): Int

    open fun onCreateNotFoundParentErrorMessage(command: C): String {
        return "Parent node not found. parentCode:${command.parentCode}."
    }

    open fun onCreateExceedMaxLevelErrorMessage(event: Created): String {
        return "Tree node level exceeds the maximum level. level:${event.level} maxLevel:${maxLevel()}"
    }

    @OnCommand
    open fun onCreate(command: C): Created {
        var code: String = generateCode()

        if (command.parentCode != ROOT_CODE) {
            require(state.children.any { it.code == command.parentCode }) {
                onCreateNotFoundParentErrorMessage(command)
            }
            code = command.parentCode.treeCode(code)
        }

        val sortId = state.children.filter { command.parentCode.isDirectChild(it.code) }
            .maxOfOrNull { it.sortId + 1 } ?: 0
        val event = command.toEvent(code = code, sortId = sortId)
        require(event.level <= maxLevel()) {
            onCreateExceedMaxLevelErrorMessage(event)
        }
        return event
    }

    open fun nodeNotFoundErrorMessage(treeCoded: TreeCoded): String {
        return "Tree node not found. code:${treeCoded.code}"
    }

    open fun hasChildErrorMessage(treeCoded: TreeCoded): String {
        return "Tree node has children. code:${treeCoded.code}"
    }

    @OnCommand
    open fun onDelete(command: D): Deleted {
        val node = state.children.firstOrNull { it.code == command.code }
        requireNotNull(node) {
            nodeNotFoundErrorMessage(command)
        }
        val childCodePrefix = command.code.childCodePrefix()
        val hasChild = state.children.any {
            it.code.startsWith(childCodePrefix)
        }
        require(!hasChild) {
            hasChildErrorMessage(command)
        }
        return command.toEvent()
    }

    @OnCommand
    open fun onUpdate(command: U): Updated {
        require(state.children.any { it.code == command.code }) {
            nodeNotFoundErrorMessage(command)
        }
        return command.toEvent()
    }

    @OnCommand
    open fun onMove(command: M): Moved {
        return command.toEvent()
    }
}
