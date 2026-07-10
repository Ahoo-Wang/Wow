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

package me.ahoo.wow.bi.expansion.type

import tools.jackson.databind.JavaType
import java.lang.reflect.Member

internal enum class Nullability {
    NON_NULL,
    NULLABLE,
    UNKNOWN,
}

internal enum class ResolvedTypeOrigin {
    KOTLIN,
    JAVA,
}

internal data class ResolvedType(
    val javaType: JavaType,
    val nullability: Nullability,
    val arguments: List<ResolvedType>,
) {
    val rawClass: Class<*>
        get() = javaType.rawClass
}

internal data class ResolvedJsonProperty(
    val serializedName: String,
    val type: ResolvedType,
    val origin: ResolvedTypeOrigin,
    val declaringMember: Member,
)
