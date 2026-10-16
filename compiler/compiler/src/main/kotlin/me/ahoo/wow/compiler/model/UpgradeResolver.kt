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

import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import me.ahoo.wow.api.annotation.Blocking
import me.ahoo.wow.api.annotation.Upgrade

/**
 * An `@Upgrade` function (decision D82) as the model processor read it: the event it upgrades, by its qualified name
 * (null when the annotation does not resolve), the revision it upgrades from, and what is wrong with its signature.
 */
internal class UpgradeFunction(
    val declaration: KSFunctionDeclaration,
    val event: String?,
    val from: Int,
    val binding: UpgradeBinding,
    val problems: List<String>,
) {
    /** How messages name the function. */
    val name: String get() = binding.function
}

/** An upgrade the generated model registers: `upgrade<event>(from) { function(node) }`. */
internal data class UpgradeBinding(
    val event: String,
    val from: Int,
    /** The function's qualified name, which the generated model calls with no instance. */
    val function: String,
    /** Whether the function takes an `ObjectNode`, to which the generated model casts the payload. */
    val objectNode: Boolean,
)

/**
 * Reads `@Upgrade` functions and checks the chains they form (decision D82): an event at revision `n` needs exactly
 * one upgrade from each of the revisions 1 to `n - 1`.
 */
internal object UpgradeResolver {
    val UPGRADE: String = Upgrade::class.java.name
    private val BLOCKING = Blocking::class.java.name
    private const val JSON_NODE = "tools.jackson.databind.JsonNode"
    private const val OBJECT_NODE = "tools.jackson.databind.node.ObjectNode"
    private val NODES = setOf(JSON_NODE, OBJECT_NODE)

    fun read(function: KSFunctionDeclaration): UpgradeFunction {
        val name = function.qualifiedName?.asString() ?: function.simpleName.asString()
        val annotation = function.annotations.first {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == UPGRADE
        }
        val arguments = annotation.arguments.associate { it.name?.asString() to it.value }
        val event = (arguments["event"] as? KSType)?.declaration?.qualifiedName?.asString()
        val from = arguments["from"] as? Int ?: 0
        val parameter = function.parameters.singleOrNull()?.type?.resolve()
        val returned = function.returnType?.resolve()
        val takesNode = function.extensionReceiver == null && parameter?.qualifiedName() in NODES
        val returnsNode = returned?.qualifiedName() in NODES && returned?.isMarkedNullable == false
        val problems = buildList {
            if (!takesNode || !returnsNode) {
                add(
                    "upgrade $name must take one JsonNode or ObjectNode (tools.jackson.databind) and return " +
                        "a non-null one",
                )
            }
            if (Modifier.SUSPEND in function.modifiers) {
                add("upgrade $name suspends: an upgrade does no I/O")
            }
            if (function.annotations.any { it.annotationType.resolve().qualifiedName() == BLOCKING }) {
                add("upgrade $name is annotated @Blocking: an upgrade does no I/O")
            }
            if (function.isPrivate()) {
                add("upgrade $name is private: the generated model calls it")
            }
            if (!isStatic(function)) {
                add(
                    "upgrade $name is a member of a class: it must be a top-level function, a member of an object " +
                        "(a companion object included) or a Java static method, since the model calls it with no " +
                        "instance",
                )
            }
        }
        val binding = UpgradeBinding(
            event = event.orEmpty(),
            from = from,
            function = name,
            objectNode = parameter?.qualifiedName() == OBJECT_NODE,
        )
        return UpgradeFunction(function, event, from, binding, problems)
    }

    private fun isStatic(function: KSFunctionDeclaration): Boolean {
        val parent = function.parentDeclaration ?: return true
        return Modifier.JAVA_STATIC in function.modifiers ||
            (parent as? KSClassDeclaration)?.classKind == ClassKind.OBJECT
    }

    private fun KSType.qualifiedName(): String? = declaration.qualifiedName?.asString()

    /**
     * The problems of the upgrades of an event, [simpleName] at [revision], declared by [upgrades]: their signatures,
     * a revision out of range, two from one revision, and a revision with none. An event above revision 1 with no
     * upgrade at all is a problem like a gap: the log may hold its earlier revisions, and nothing would upgrade them.
     */
    fun chainProblems(simpleName: String, revision: Int, upgrades: List<UpgradeFunction>): List<String> {
        if (upgrades.isEmpty()) {
            return if (revision > 1) {
                listOf(
                    "event $simpleName is at revision $revision but has no @Upgrade: declare one from each of the " +
                        "revisions ${(1 until revision).joinToString()}, as " +
                        "@Upgrade(event = $simpleName::class, from = 1) on a function from JsonNode to JsonNode",
                )
            } else {
                emptyList()
            }
        }
        val range = if (revision > 1) "so its upgrades go from revision 1 to ${revision - 1}" else "which needs none"
        val outOfRange = upgrades.filter { it.from !in 1 until revision }.map {
            "upgrade ${it.name} of $simpleName from revision ${it.from}: $simpleName is at revision $revision, $range"
        }
        val duplicates = upgrades.groupBy { it.from }.filterValues { it.size > 1 }.map { (from, twice) ->
            "two upgrades of $simpleName from revision $from: ${twice.joinToString { it.name }}"
        }
        val missing = (1 until revision) - upgrades.map { it.from }.toSet()
        val gaps = if (missing.isEmpty()) {
            emptyList()
        } else {
            listOf(
                "event $simpleName is at revision $revision but has no upgrade from revision ${missing.joinToString()}"
            )
        }
        return upgrades.flatMap { it.problems } + outOfRange + duplicates + gaps
    }
}
