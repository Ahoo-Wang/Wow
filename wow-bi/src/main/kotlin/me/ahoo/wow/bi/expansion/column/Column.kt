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

package me.ahoo.wow.bi.expansion.column

import me.ahoo.wow.bi.expansion.SqlTypeMapping.isSimple
import me.ahoo.wow.bi.expansion.SqlTypeMapping.toSqlType
import me.ahoo.wow.naming.NamingConverter
import tools.jackson.databind.JavaType

interface Column {
    /**
     * property name
     */
    val name: String
    val parent: Column?
    val inherited: Boolean
        get() = true
    val type: JavaType
    val isSimple: Boolean
        get() = type.rawClass.isSimple

    val isCollection: Boolean
        get() = type.isCollectionLikeType || type.isArrayType

    val isMap: Boolean
        get() = type.isMapLikeType
    val isNested: Boolean
        get() = !isSimple && !isCollection && !isMap

    val targetName: String
        get() = NamingConverter.PASCAL_TO_SNAKE.convert(name)

    val targetFullName: String
        get() {
            if (parent == null || parent?.parent == null) {
                return targetName
            }
            return "${parent!!.targetFullName}__$targetName"
        }
    val sqlType: String
        get() = type.rawClass.toSqlType()
    val extractExpression: String
        get() {
            if (parent == null) {
                return name
            }
            val parentTargetFullName = parent!!.targetFullName
            if (isCollection) {
                return "JSONExtractArrayRaw($parentTargetFullName, '$name')"
            }

            if (isNested) {
                return "JSONExtractString($parentTargetFullName,'$name')"
            }
            return "JSONExtract($parentTargetFullName,'$name', '$sqlType')"
        }

    val expression: String
        get() = "$extractExpression AS $targetFullName"

    companion object {
        fun JavaType.mapColumn(name: String, parent: Column?): Column {
            require(isMapLikeType) { "JavaType [$this] is not map like type." }
            if (contentType.rawClass.isSimple) {
                return SimpleMapColumn(name, type = contentType, parent = parent)
            }
            return StringMapColumn(name, type = contentType, parent = parent)
        }
    }
}
