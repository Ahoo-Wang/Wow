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

import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.Modifier
import me.ahoo.wow.manifest.QueryFieldNames

/**
 * The query fields of an aggregate's state (work package P.2): `<State>Properties`, an object extending
 * `wow-query-api`'s `SnapshotProperties` (the snapshot's system fields) with a `QueryField` per path of the state
 * a snapshot query can name, `state.<path>`. Generated only when the module has `wow-query-api` on its class path.
 *
 * A path follows what Jackson writes and the state schema of the Manifest describes: each public property with a
 * backing field, under its `@JsonProperty` name, without the `@JsonIgnore`d ones and those a class's
 * `@JsonIgnoreProperties` names. It descends into a property of an application class, and into the element class of
 * a collection or an array (an element's fields are named inside its `ELEMENT_MATCH`, which the DSL reads relative to
 * the element); a map, an enum, an interface, an abstract or sealed class, a value class and the `kotlin.` and
 * `java.` types are leaves, and so is a class already on the path. Each constant is named after its path, as
 * `wow-manifest`'s [QueryFieldNames] names it, the same function the api module's generated `<Context>Contract` names
 * the state schema's paths with (decision D259): the JSON property names in upper snake case, a nested one after
 * `__`: `STATE__ITEMS__PRODUCT_ID`.
 */
internal object QueryPropertiesSource {
    const val SNAPSHOT_PROPERTIES = "me.ahoo.wow.api.query.SnapshotProperties"
    private const val QUERY_FIELD = "me.ahoo.wow.api.query.QueryField"
    private const val STATE = QueryFieldNames.STATE
    private const val JSON_PROPERTY = "com.fasterxml.jackson.annotation.JsonProperty"
    private const val JSON_IGNORE = "com.fasterxml.jackson.annotation.JsonIgnore"
    private const val JSON_IGNORE_PROPERTIES = "com.fasterxml.jackson.annotation.JsonIgnoreProperties"
    private val COLLECTIONS = setOf(
        "kotlin.collections.Iterable",
        "kotlin.collections.Collection",
        "kotlin.collections.List",
        "kotlin.collections.Set",
        "kotlin.collections.MutableIterable",
        "kotlin.collections.MutableCollection",
        "kotlin.collections.MutableList",
        "kotlin.collections.MutableSet",
        "kotlin.Array",
        "java.util.List",
        "java.util.Set",
        "java.util.Collection",
    )

    /** One generated constant: its [name] and the logical [path] it holds. */
    data class Property(val name: String, val path: String)

    /** Whether the module has `wow-query-api` on its class path. */
    fun available(resolver: Resolver): Boolean =
        resolver.getClassDeclarationByName(resolver.getKSNameFromString(SNAPSHOT_PROPERTIES)) != null

    /** Writes [state]'s `<State>Properties`, unless [generated], the states already written, holds it. */
    fun generate(codeGenerator: CodeGenerator, state: KSClassDeclaration, generated: MutableSet<String>) {
        if (!generated.add(state.qualifiedName!!.asString())) {
            return
        }
        codeGenerator.createNewFile(
            Dependencies(aggregating = false, sources = listOfNotNull(state.containingFile).toTypedArray()),
            state.packageName.asString(),
            className(state),
        ).bufferedWriter().use { it.write(render(state, properties(state))) }
    }

    /** The name of the object generated for [state]: its simple names joined, then `Properties`. */
    fun className(state: KSClassDeclaration): String =
        state.qualifiedName!!.asString().removePrefix("${state.packageName.asString()}.").replace(".", "") +
            "Properties"

    /** The paths of [state] a query names, in declaration order, each once. */
    fun properties(state: KSClassDeclaration): List<Property> {
        val properties = linkedMapOf<String, Property>()
        collect(state, STATE, listOf(state.qualifiedName!!.asString()), properties)
        return properties.values.toList()
    }

    fun render(state: KSClassDeclaration, properties: List<Property>): String = buildString {
        val packageName = state.packageName.asString()
        appendLine("// Generated by wow-compiler (ModelSymbolProcessor); do not edit.")
        if (packageName.isNotEmpty()) {
            appendLine("package $packageName")
        }
        appendLine()
        appendLine("/**")
        appendLine(" * The query fields of [${state.qualifiedName!!.asString()}] snapshots: the system fields of")
        appendLine(" * [$SNAPSHOT_PROPERTIES] and the state's own, `state.*`.")
        appendLine(" */")
        appendLine("public object ${className(state)} : $SNAPSHOT_PROPERTIES() {")
        properties.forEach {
            appendLine("    public val ${it.name}: $QUERY_FIELD = $QUERY_FIELD(\"${it.path}\")")
        }
        appendLine("}")
    }

    private fun collect(
        owner: KSClassDeclaration,
        path: String,
        visited: List<String>,
        properties: MutableMap<String, Property>,
    ) {
        val ignored = owner.ignoredProperties()
        owner.getAllProperties().filter { it.isQueryable(ignored) }.forEach { property ->
            val wireName = property.wireName()
            if (!QueryFieldNames.isSegment(wireName)) return@forEach
            val childPath = "$path.$wireName"
            val childName = QueryFieldNames.constantOf(childPath)
            if (properties.putIfAbsent(childName, Property(childName, childPath)) != null) return@forEach
            val nested = property.type.resolve().nestedClass() ?: return@forEach
            val qualified = nested.qualifiedName!!.asString()
            if (qualified !in visited) {
                collect(nested, childPath, visited + qualified, properties)
            }
        }
    }

    private fun KSPropertyDeclaration.isQueryable(ignored: Set<String>): Boolean =
        isPublic() && hasBackingField && extensionReceiver == null &&
            simpleName.asString() !in ignored && wireName() !in ignored &&
            jacksonAnnotations().none { it.isJsonIgnore() }

    private fun KSPropertyDeclaration.wireName(): String =
        jacksonAnnotations().filter { it.qualifiedName() == JSON_PROPERTY }
            .firstNotNullOfOrNull { (it.argument("value") as? String)?.takeIf(String::isNotEmpty) }
            ?: simpleName.asString()

    /** The class whose properties a path continues into: the type itself, or a collection's element type. */
    private fun KSType.nestedClass(): KSClassDeclaration? {
        (declaration as? KSTypeAlias)?.let { return it.type.resolve().nestedClass() }
        val qualified = declaration.qualifiedName?.asString() ?: return null
        if (qualified in COLLECTIONS) {
            return arguments.firstOrNull()?.type?.resolve()?.nestedClass()
        }
        val type = declaration as? KSClassDeclaration ?: return null
        val leaf = type.classKind != ClassKind.CLASS ||
            qualified.startsWith("kotlin.") || qualified.startsWith("java.") || qualified.startsWith("javax.") ||
            type.modifiers.any { it == Modifier.ABSTRACT || it == Modifier.SEALED || it == Modifier.VALUE } ||
            type.modifiers.contains(Modifier.INLINE)
        return if (leaf) null else type
    }

    /** Jackson annotations on the property, its getter, or its primary-constructor parameter. */
    private fun KSPropertyDeclaration.jacksonAnnotations(): List<KSAnnotation> {
        val name = simpleName.asString()
        val parameter = (parentDeclaration as? KSClassDeclaration)?.primaryConstructor?.parameters
            ?.firstOrNull { it.name?.asString() == name }?.annotations.orEmpty()
        return (annotations + getter?.annotations.orEmpty() + parameter)
            .filter { it.qualifiedName() == JSON_PROPERTY || it.qualifiedName() == JSON_IGNORE }
            .toList()
    }

    private fun KSClassDeclaration.ignoredProperties(): Set<String> = annotations
        .filter { it.qualifiedName() == JSON_IGNORE_PROPERTIES }
        .flatMap { (it.argument("value") as? List<*>).orEmpty() }
        .filterIsInstance<String>()
        .toSet()

    private fun KSAnnotation.isJsonIgnore(): Boolean =
        qualifiedName() == JSON_IGNORE && argument("value") as? Boolean ?: true

    private fun KSAnnotation.qualifiedName(): String? =
        annotationType.resolve().declaration.qualifiedName?.asString()

    private fun KSAnnotation.argument(name: String): Any? = arguments.firstOrNull { it.name?.asString() == name }?.value
}
