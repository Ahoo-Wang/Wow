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

package me.ahoo.wow.compiler.model

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.naming.TypeNames

/** The aggregate's name: its `@Name`, else its simple name in `snake_case` ([TypeNames.aggregateName]). */
@OptIn(KspExperimental::class)
internal fun KSClassDeclaration.toName(): String =
    TypeNames.aggregateName(simpleName.asString(), getAnnotationsByType(Name::class).firstOrNull()?.value)

/** The JVM binary name of the class (`sample.Commands$Clear`), which keys its schema in the Manifest fragment. */
internal fun KSDeclaration.binaryName(): String {
    val names = generateSequence(this) { it.parentDeclaration }.map { it.simpleName.asString() }
    val packageName = packageName.asString()
    return (if (packageName.isEmpty()) "" else "$packageName.") + names.toList().asReversed().joinToString("$")
}
