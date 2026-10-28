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
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.models.tree.DEPARTMENT_CODE_DELIMITER
import me.ahoo.wow.models.tree.Flat
import me.ahoo.wow.models.tree.TreeCoded
import me.ahoo.wow.models.tree.TreeCoded.Companion.childCodePrefix
import me.ahoo.wow.models.tree.TreeCoded.Companion.isDirectChild
import me.ahoo.wow.models.tree.TreeCoded.Companion.isRootNode
import me.ahoo.wow.models.tree.TreeCoded.Companion.parentCode
import me.ahoo.wow.models.tree.TreeCoded.Companion.treeCode
import me.ahoo.wow.models.tree.command.Create
import me.ahoo.wow.models.tree.command.Created
import me.ahoo.wow.models.tree.command.Delete
import me.ahoo.wow.models.tree.command.Move
import me.ahoo.wow.models.tree.command.Update

/**
 * A reusable aggregate of one tree, such as a tenant's categories or departments: subclass it with the concrete
 * commands, annotate the subclass `@AggregateRoot`, and the compiler binds the command functions declared here, with
 * the type arguments the subclass gives (its state [T], and its commands [C], [U], [D] and [M]):
 *
 * ```kotlin
 * @AggregateRoot
 * class Category(state: CategoryState) :
 *     Tree<CategoryState, CreateCategory, UpdateCategory, DeleteCategory, MoveCategory>(state) {
 *     override fun maxLevel(): Int = 3
 * }
 * ```
 *
 * A command that breaks a rule of the tree is rejected with a [TreeRejections] code, its message from the overridable
 * `...ErrorMessage` functions. The `verify...` hooks run after the tree's own rules and may reject too, after I/O if
 * they need it (they suspend). Creating a node answers the caller with the node's code under the result key `code`
 * ([DecideScope.putResult]).
 */
abstract class Tree<T : TreeState<*, *, *, *, *>, C : Create<*>, U : Update<*>, D : Delete<*>, M : Move<*>>(
    protected val state: T,
) {
    /**
     * The new node's own code, the last segment of its tree code: by default a new global id of the decision
     * ([DecideScope.ids]) without [DEPARTMENT_CODE_DELIMITER], which separates the segments. It must be neither empty
     * nor hold the delimiter.
     */
    protected open fun generateCode(scope: DecideScope): String =
        scope.ids.next().replace(DEPARTMENT_CODE_DELIMITER, "")

    /** The deepest level a node may have; the first level is 1. */
    protected abstract fun maxLevel(): Int

    protected open fun onCreateNotFoundParentErrorMessage(command: C): String =
        "Parent node not found. parentCode:${command.parentCode}."

    protected open fun onCreateExceedMaxLevelErrorMessage(event: Created): String =
        "Tree node level exceeds the maximum level. level:${event.level} maxLevel:${maxLevel()}"

    protected open fun nodeNotFoundErrorMessage(treeCoded: TreeCoded): String =
        "Tree node not found. code:${treeCoded.code}"

    protected open fun hasChildErrorMessage(treeCoded: TreeCoded): String =
        "Tree node has children. code:${treeCoded.code}"

    protected open fun invalidMoveErrorMessage(command: M): String =
        "Only existing sibling nodes can be moved, each once. codes:${command.codes}"

    /** Rejects a creation the tree's rules allow; null accepts it. */
    protected open suspend fun verifyCreate(command: C, scope: DecideScope): Rejection? = null

    /** Rejects an update the tree's rules allow; null accepts it. */
    protected open suspend fun verifyUpdate(command: U, scope: DecideScope): Rejection? = null

    /** Rejects a deletion the tree's rules allow; null accepts it. */
    protected open suspend fun verifyDelete(command: D, scope: DecideScope): Rejection? = null

    /**
     * Creates a node under its parent, after its siblings, with a generated code ([generateCode]); the parent must
     * exist and the node must not be deeper than [maxLevel].
     */
    @OnCommand
    open suspend fun onCreate(command: C, scope: DecideScope): Decision {
        val parentCode = command.parentCode
        if (!parentCode.isRootNode() && state.children.none { it.code == parentCode }) {
            return reject(
                TreeRejections.PARENT_NOT_FOUND,
                onCreateNotFoundParentErrorMessage(command),
                PARENT_CODE to parentCode,
            )
        }
        val own = generateCode(scope)
        check(own.isNotEmpty() && DEPARTMENT_CODE_DELIMITER !in own) {
            "generateCode() gave [$own]: a node's own code is not empty and holds no '$DEPARTMENT_CODE_DELIMITER'."
        }
        val sortId = state.children.filter { parentCode.isDirectChild(it.code) }.maxOfOrNull { it.sortId + 1 } ?: 0
        val event = command.toEvent(code = parentCode.treeCode(own), sortId = sortId)
        if (event.level > maxLevel()) {
            return reject(
                TreeRejections.MAX_LEVEL_EXCEEDED,
                onCreateExceedMaxLevelErrorMessage(event),
                LEVEL to event.level,
                MAX_LEVEL to maxLevel(),
            )
        }
        verifyCreate(command, scope)?.let { return Decision.Rejected(it) }
        scope.putResult(Flat::code.name, event.code)
        return Decision.events(event)
    }

    /** Updates an existing node. */
    @OnCommand
    open suspend fun onUpdate(command: U, scope: DecideScope): Decision {
        val previous = state.children.firstOrNull { it.code == command.code }
            ?: return reject(TreeRejections.NODE_NOT_FOUND, nodeNotFoundErrorMessage(command), CODE to command.code)
        verifyUpdate(command, scope)?.let { return Decision.Rejected(it) }
        return Decision.events(command.toEvent(previous))
    }

    /** Deletes an existing node without children. */
    @OnCommand
    open suspend fun onDelete(command: D, scope: DecideScope): Decision {
        val previous = state.children.firstOrNull { it.code == command.code }
            ?: return reject(TreeRejections.NODE_NOT_FOUND, nodeNotFoundErrorMessage(command), CODE to command.code)
        val childCodePrefix = command.code.childCodePrefix()
        if (state.children.any { it.code.startsWith(childCodePrefix) }) {
            return reject(TreeRejections.NODE_HAS_CHILDREN, hasChildErrorMessage(command), CODE to command.code)
        }
        verifyDelete(command, scope)?.let { return Decision.Rejected(it) }
        return Decision.events(command.toEvent(previous))
    }

    /** Orders sibling nodes: each code must be of an existing node, given once, and all of them of one parent. */
    @OnCommand
    open fun onMove(command: M): Decision {
        val codes = command.codes
        val existing = state.children.mapTo(HashSet()) { it.code }
        val valid = codes.isNotEmpty() &&
            codes.toSet().size == codes.size &&
            codes.all { it in existing } &&
            codes.map { it.parentCode() }.distinct().size == 1
        if (!valid) {
            return reject(TreeRejections.INVALID_MOVE, invalidMoveErrorMessage(command), CODES to codes)
        }
        return Decision.events(command.toEvent())
    }

    private fun reject(code: String, message: String, vararg extensions: Pair<String, Any?>): Decision =
        Decision.reject(code, message, extensions = extensions.toMap())

    private companion object {
        const val CODE = "code"
        const val CODES = "codes"
        const val PARENT_CODE = "parentCode"
        const val LEVEL = "level"
        const val MAX_LEVEL = "maxLevel"
    }
}
