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

const val DEPARTMENT_CODE_DELIMITER = "-"
const val ROOT_CODE = ""

fun childCodePrefix(parentCode: String): String {
    return if (parentCode == ROOT_CODE) {
        ""
    } else {
        "$parentCode$DEPARTMENT_CODE_DELIMITER"
    }
}

fun treeCode(parentCode: String, childCode: String): String {
    return "${childCodePrefix(parentCode)}$childCode"
}

interface TreeCoded {
    val code: String
}

interface Info : TreeCoded, Named, Comparable<Info> {
    val sortId: Int

    @JsonIgnore
    fun isRoot(): Boolean {
        return code == ROOT_CODE
    }

    val level: Int
        get() = if (isRoot()) {
            0
        } else {
            code.split(DEPARTMENT_CODE_DELIMITER).size
        }

    fun isDirectChild(child: Info): Boolean {
        if (isRoot()) {
            return child.level == 1
        }
        val childCodePrefix = childCodePrefix(code)
        return level + 1 == child.level && child.code.startsWith(childCodePrefix)
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
