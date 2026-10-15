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

package me.ahoo.wow.models.tree

import com.fasterxml.jackson.annotation.JsonIgnore
import me.ahoo.wow.models.common.NameCapable
import me.ahoo.wow.models.tree.TreeCoded.Companion.isDirectChild

/** What separates the levels of a tree code: `l1-l2-l3` is the node `l3` under `l1-l2`. */
const val DEPARTMENT_CODE_DELIMITER = "-"

/** The code of the (virtual) root of a tree: its first level's nodes are its direct children. */
const val ROOT_CODE = ""

/**
 * A node of a tree, named by its [code]: the codes of its ancestors and its own, joined by
 * [DEPARTMENT_CODE_DELIMITER]. Its [level] is the number of codes, 0 for the root.
 */
interface TreeCoded {
    val code: String

    @JsonIgnore
    fun isRoot(): Boolean = code.isRootNode()

    val level: Int
        get() = code.nodeLevel()

    companion object {
        /** Whether this code is the root's. */
        fun String.isRootNode(): Boolean = this == ROOT_CODE

        /** The code of this code's parent; the root's for the root and the first level. */
        fun String.parentCode(): String =
            if (isRootNode()) {
                ROOT_CODE
            } else {
                split(DEPARTMENT_CODE_DELIMITER).dropLast(1).joinToString(DEPARTMENT_CODE_DELIMITER)
            }

        /** The level of this code: 0 for the root. */
        fun String.nodeLevel(): Int = if (isRootNode()) 0 else split(DEPARTMENT_CODE_DELIMITER).size

        /** What the codes of this code's descendants start with. */
        fun String.childCodePrefix(): String = if (isRootNode()) ROOT_CODE else "$this$DEPARTMENT_CODE_DELIMITER"

        /** The code of this code's child whose own code is [childCode]. */
        fun String.treeCode(childCode: String): String = "${childCodePrefix()}$childCode"

        /** Whether [childCode] is a direct child of this code. */
        fun String.isDirectChild(childCode: String): Boolean {
            if (this == childCode) {
                return false
            }
            val parentLevel = nodeLevel()
            val childLevel = childCode.nodeLevel()
            if (parentLevel == 0) {
                return childLevel == 1
            }
            return parentLevel + 1 == childLevel && childCode.startsWith(childCodePrefix())
        }
    }
}

/** A named node of a tree, ordered by level, then [sortId] among its siblings, then code. */
interface Info : TreeCoded, NameCapable, Comparable<Info> {
    val sortId: Int

    /** Whether [child] is a direct child of this node. */
    fun isDirectChild(child: Info): Boolean = code.isDirectChild(child.code)

    @Suppress("ReturnCount")
    override fun compareTo(other: Info): Int {
        val levelCompared = level.compareTo(other.level)
        if (levelCompared != 0) {
            return levelCompared
        }
        val sortIdCompared = sortId.compareTo(other.sortId)
        if (sortIdCompared != 0) {
            return sortIdCompared
        }
        return code.compareTo(other.code)
    }
}
