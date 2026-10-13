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
import java.util.regex.Pattern

/** Splits before every upper-case letter, as 9.x's `PascalCaseStrategy` does (wow-metadata). */
private val UPPERCASE_SEPARATOR: Pattern = Pattern.compile("(?=[A-Z])")

/**
 * `PascalCase` to `snake_case` (`CartItem` → `cart_item`), the result of 9.x's `NamingConverter.PASCAL_TO_SNAKE`, so an
 * aggregate keeps the name 9.x gave it. Ported here so the compiler does not depend on wow-metadata (decision D85).
 */
internal fun String.pascalToSnake(): String =
    UPPERCASE_SEPARATOR.split(this).joinToString(separator = "_") { it.lowercase() }

/** The aggregate's name: its `@Name`, else its simple name in `snake_case`, as 9.x's `AggregateRootResolver` names it. */
@OptIn(KspExperimental::class)
internal fun KSClassDeclaration.toName(): String =
    getAnnotationsByType(Name::class).firstOrNull()?.value
        ?: simpleName.asString().pascalToSnake()

/** The JVM binary name of the class (`sample.Commands$Clear`), which keys its schema in the Manifest fragment. */
internal fun KSDeclaration.binaryName(): String {
    val names = generateSequence(this) { it.parentDeclaration }.map { it.simpleName.asString() }
    val packageName = packageName.asString()
    return (if (packageName.isEmpty()) "" else "$packageName.") + names.toList().asReversed().joinToString("$")
}
