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
import me.ahoo.wow.api.command.CommandResultAccessor
import me.ahoo.wow.models.tree.Flat
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
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

abstract class Tree<T : TreeState<*, *, *, *, *>, C : Create<*>, U : Update<*>, D : Delete<*>, M : Move<*>>(
    protected val state: T
) {

    protected abstract fun generateCode(): String

    protected abstract fun maxLevel(): Int

    protected open fun onCreateNotFoundParentErrorMessage(command: C): String {
        return "Parent node not found. parentCode:${command.parentCode}."
    }

    protected open fun onCreateExceedMaxLevelErrorMessage(event: Created): String {
        return "Tree node level exceeds the maximum level. level:${event.level} maxLevel:${maxLevel()}"
    }

    protected open fun verifyCreate(command: C) = Mono.empty<Void>()

    @OnCommand
    protected open fun onCreate(command: C, commandResultAccessor: CommandResultAccessor): Mono<Created> {
        var code: String = generateCode()

        if (command.parentCode != ROOT_CODE) {
            check(state.children.any { it.code == command.parentCode }) {
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
        return verifyCreate(command).then(
            Mono.defer {
                commandResultAccessor.setCommandResult(Flat::code.name, event.code)
                event.toMono()
            }
        )
    }

    protected open fun nodeNotFoundErrorMessage(treeCoded: TreeCoded): String {
        return "Tree node not found. code:${treeCoded.code}"
    }

    protected open fun hasChildErrorMessage(treeCoded: TreeCoded): String {
        return "Tree node has children. code:${treeCoded.code}"
    }

    protected open fun verifyDelete(command: D) = Mono.empty<Void>()

    @OnCommand
    protected open fun onDelete(command: D): Mono<Deleted> {
        val previous = state.children.firstOrNull { it.code == command.code }
        checkNotNull(previous) {
            nodeNotFoundErrorMessage(command)
        }
        val childCodePrefix = command.code.childCodePrefix()
        val hasChild = state.children.any {
            it.code.startsWith(childCodePrefix)
        }
        check(!hasChild) {
            hasChildErrorMessage(command)
        }
        return verifyDelete(command).then(
            Mono.defer {
                command.toEvent(previous).toMono()
            }
        )
    }

    protected open fun verifyUpdate(command: U) = Mono.empty<Void>()

    @OnCommand
    protected open fun onUpdate(command: U): Mono<Updated> {
        val previous = state.children.firstOrNull { it.code == command.code }
        checkNotNull(previous) {
            nodeNotFoundErrorMessage(command)
        }
        return verifyUpdate(command).then(
            Mono.defer {
                command.toEvent(previous).toMono()
            }
        )
    }

    @OnCommand
    protected open fun onMove(command: M): Mono<Moved> {
        return command.toEvent().toMono()
    }
}
