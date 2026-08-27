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

package me.ahoo.wow.bi.expansion.plan

import me.ahoo.wow.naming.NamingConverter
import java.security.MessageDigest

internal const val RAW_TARGET_PREFIX: String = "__raw__"

internal fun childPath(parentPath: String, name: String): String =
    if (parentPath.isBlank()) name else "$parentPath.$name"

internal fun childTargetName(parent: PlanningNode, name: String): String {
    val propertyName = NamingConverter.PASCAL_TO_SNAKE.convert(name)
    return if (parent.depth == 0) propertyName else "${parent.targetName}__$propertyName"
}

internal fun rawTargetName(targetName: String): String = "$RAW_TARGET_PREFIX$targetName"

internal fun cursorTargetName(targetName: String): String =
    "${ExpansionViewPlan.CURSOR_TARGET_PREFIX}$targetName"

internal fun encodePointerSegment(value: String): String =
    value.replace("~", "~0").replace("/", "~1")

internal fun relativeTargetName(anchorTargetName: String, targetName: String): String =
    if (anchorTargetName == STATE_COLUMN) {
        targetName
    } else {
        targetName.removePrefix("${anchorTargetName}__")
    }

internal fun String.toObjectNameSegment(): String {
    if (all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
        return this
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .take(OBJECT_NAME_HASH_BYTES)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return "field_$digest"
}

private const val OBJECT_NAME_HASH_BYTES: Int = 16
