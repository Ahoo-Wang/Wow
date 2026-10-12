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

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier

/**
 * What a decide function or an after hook can produce, read from its return type for the Manifest's `produces`:
 * the qualified names of the classes it yields as events. `Unit` yields none; a collection, array or
 * Reactor publisher yields what its element type yields; a sealed type yields what its subclasses yield; any other
 * class yields itself. A type that does not say what it holds (`Any`, `Decision`, a star projection) gives null.
 */
internal object EventYield {
    private val NOTHING = setOf("kotlin.Unit", "kotlin.Nothing", "java.lang.Void")
    private val UNKNOWN = setOf("kotlin.Any", "java.lang.Object", "me.ahoo.wow.api.decide.Decision")
    private val CONTAINERS = setOf(
        "kotlin.collections.List",
        "kotlin.collections.MutableList",
        "kotlin.collections.Collection",
        "kotlin.collections.MutableCollection",
        "kotlin.collections.Iterable",
        "kotlin.collections.MutableIterable",
        "kotlin.collections.Set",
        "kotlin.collections.MutableSet",
        "kotlin.Array",
        "reactor.core.publisher.Mono",
        "reactor.core.publisher.Flux",
        "org.reactivestreams.Publisher",
    )

    /** What [type] yields; null for a star projection, as for any type that does not say. */
    fun of(type: KSType?): List<String>? {
        val declaration = (type ?: return null).declaration
        val name = declaration.qualifiedName?.asString() ?: return null
        return when {
            name in NOTHING -> emptyList()
            name in UNKNOWN -> null
            name in CONTAINERS -> of(type.arguments.single().type?.resolve())
            declaration is KSClassDeclaration && Modifier.SEALED in declaration.modifiers -> sealed(declaration)
            else -> listOf(name)
        }
    }

    private fun sealed(declaration: KSClassDeclaration): List<String>? {
        val yielded = declaration.getSealedSubclasses().map { of(it.asStarProjectedType()) }.toList()
        return if (null in yielded) null else yielded.flatMap { it.orEmpty() }.distinct()
    }
}
