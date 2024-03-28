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
import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.models.tree.TreeCoded.Companion.isDirectChild

const val DEPARTMENT_CODE_DELIMITER = "-"
const val ROOT_CODE = ""

interface TreeCoded {
    val code: String

    @JsonIgnore
    fun isRoot(): Boolean {
        return code.isRootNode()
    }

    val level: Int
        get() = if (isRoot()) {
            0
        } else {
            code.split(DEPARTMENT_CODE_DELIMITER).size
        }

    companion object {

        fun String.isRootNode(): Boolean {
            return this == ROOT_CODE
        }

        fun String.nodeLevel(): Int {
            return if (isRootNode()) {
                0
            } else {
                split(DEPARTMENT_CODE_DELIMITER).size
            }
        }

        fun String.childCodePrefix(): String {
            return if (this == ROOT_CODE) {
                ""
            } else {
                "$this$DEPARTMENT_CODE_DELIMITER"
            }
        }

        fun String.treeCode(childCode: String): String {
            return "${childCodePrefix()}$childCode"
        }

        fun String.isDirectChild(childCode: String): Boolean {
            if (this == childCode) {
                return false
            }
            val parentLevel = nodeLevel()
            val childLevel = childCode.nodeLevel()
            if (parentLevel == 0) {
                return childLevel == 1
            }
            val childCodePrefix = childCodePrefix()
            return parentLevel + 1 == childLevel && childCode.startsWith(childCodePrefix)
        }
    }
}

interface Info : TreeCoded, Named, Comparable<Info> {
    val sortId: Int

    fun isDirectChild(child: Info): Boolean {
        return code.isDirectChild(child.code)
    }

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
