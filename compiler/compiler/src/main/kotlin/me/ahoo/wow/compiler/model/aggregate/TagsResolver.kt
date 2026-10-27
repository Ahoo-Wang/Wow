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

package me.ahoo.wow.compiler.model.aggregate

import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import me.ahoo.wow.api.annotation.Blocking
import me.ahoo.wow.api.annotation.Tags

/**
 * The state member marked `@Tags`, which derives the aggregate's tags from its state: the generated
 * model calls it in `tags { state -> … }`. [member] is its name; [property] whether it is a property, read without a
 * call.
 */
internal data class TagsBinding(val member: String, val property: Boolean)

/**
 * Finds and checks the `@Tags` member of an aggregate's state: at most one, a function without
 * parameters or a property, neither private, extension nor suspending, returning `Map<String, C<String>>` where `C`
 * is `List`, `Set` or `Collection` (mutable or not, none of the types nullable).
 */
internal object TagsResolver {
    private val TAGS = Tags::class.java.name
    private val BLOCKING = Blocking::class.java.name
    private const val WHERE = "@Tags"
    private val MAPS = setOf("kotlin.collections.Map", "kotlin.collections.MutableMap", "java.util.Map")
    private val COLLECTIONS = setOf(
        "kotlin.collections.List",
        "kotlin.collections.MutableList",
        "kotlin.collections.Set",
        "kotlin.collections.MutableSet",
        "kotlin.collections.Collection",
        "kotlin.collections.MutableCollection",
        "java.util.List",
        "java.util.Set",
        "java.util.Collection",
    )
    private val STRINGS = setOf("kotlin.String", "java.lang.String")
    private const val RETURNS = "Map<String, List<String>> (or Set or Collection values)"

    /** The `@Tags` member of [state], or null when it has none or it has a problem, added to [problems]. */
    fun of(state: KSClassDeclaration, problems: MutableList<String>): TagsBinding? {
        val functions = state.getAllFunctions().filter { it.isTags() }.toList()
        val properties = state.getAllProperties().filter { it.isTags() }.toList()
        val count = functions.size + properties.size
        if (count > 1) {
            val names = (functions.map { "${it.simpleName.asString()}()" } + properties.map { it.simpleName.asString() })
            problems += "the state ${state.simpleName.asString()} marks ${names.joinToString()} $WHERE: a state has " +
                "at most one member deriving its tags"
            return null
        }
        functions.singleOrNull()?.let { return function(it, problems) }
        return properties.singleOrNull()?.let { property(it, problems) }
    }

    private fun function(function: KSFunctionDeclaration, problems: MutableList<String>): TagsBinding? {
        val name = function.simpleName.asString()
        val where = "$WHERE function $name"
        val found = listOfNotNull(
            "$where takes parameters: it reads the state only".takeIf { function.parameters.isNotEmpty() },
            "$where is an extension: it is a member of the state".takeIf { function.extensionReceiver != null },
            "$where suspends: deriving tags does no I/O".takeIf { Modifier.SUSPEND in function.modifiers },
            "$where is annotated @Blocking: deriving tags does no I/O".takeIf {
                function.annotations.any { it.annotationType.resolve().qualifiedName == BLOCKING }
            },
            "$where is private: the generated model calls it".takeIf { function.isPrivate() },
            returnProblem(where, function.returnType?.resolve()),
        )
        problems += found
        return TagsBinding(name, property = false).takeIf { found.isEmpty() }
    }

    private fun property(property: KSPropertyDeclaration, problems: MutableList<String>): TagsBinding? {
        val name = property.simpleName.asString()
        val where = "$WHERE property $name"
        val found = listOfNotNull(
            "$where is an extension: it is a member of the state".takeIf { property.extensionReceiver != null },
            "$where is private: the generated model reads it".takeIf { property.isPrivate() },
            returnProblem(where, property.type.resolve()),
        )
        problems += found
        return TagsBinding(name, property = true).takeIf { found.isEmpty() }
    }

    private fun returnProblem(where: String, type: KSType?): String? =
        if (type == null || !type.isTagsMap()) "$where returns ${type?.render() ?: "nothing"}, not $RETURNS" else null

    private fun KSType.isTagsMap(): Boolean {
        if (isMarkedNullable || qualifiedName !in MAPS || arguments.size != 2) {
            return false
        }
        val key = arguments[0].type?.resolve() ?: return false
        val values = arguments[1].type?.resolve() ?: return false
        val element = values.arguments.singleOrNull()?.type?.resolve() ?: return false
        return key.isString() && !values.isMarkedNullable &&
            values.qualifiedName in COLLECTIONS && element.isString()
    }

    private fun KSType.isString(): Boolean = !isMarkedNullable && qualifiedName in STRINGS

    private val KSType.qualifiedName: String? get() = declaration.qualifiedName?.asString()

    private fun KSType.render(): String {
        val name = declaration.simpleName.asString()
        val rendered = if (arguments.isEmpty()) {
            name
        } else {
            name + arguments.joinToString(", ", "<", ">") { it.type?.resolve()?.render() ?: "*" }
        }
        return if (isMarkedNullable) "$rendered?" else rendered
    }

    private fun KSAnnotated.isTags(): Boolean = annotations.any {
        it.annotationType.resolve().qualifiedName == TAGS
    }
}
