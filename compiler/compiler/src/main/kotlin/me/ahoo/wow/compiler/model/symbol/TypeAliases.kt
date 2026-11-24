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

package me.ahoo.wow.compiler.model.symbol

import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeParameter

/**
 * The type [alias] stands for, its type parameters replaced by this type's arguments: `LinkedHashSet<Line>` is
 * `java.util.LinkedHashSet<Line>`, not a set of the alias's own parameter.
 */
internal fun KSType.aliased(alias: KSTypeAlias): KSType {
    val target = alias.type.resolve()
    val parameters = alias.typeParameters.map { it.name.asString() }
    if (parameters.isEmpty()) return target
    return target.replace(
        target.arguments.map { argument ->
            // A star projection has no type; a parameter of the target is one of the alias's own.
            val parameter = argument.type?.resolve()?.declaration as? KSTypeParameter
            if (parameter == null) argument else arguments[parameters.indexOf(parameter.name.asString())]
        },
    )
}
