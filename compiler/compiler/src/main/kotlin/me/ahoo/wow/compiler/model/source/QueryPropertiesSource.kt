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

package me.ahoo.wow.compiler.model.source

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.symbol.Variance
import me.ahoo.wow.compiler.model.KotlinNames
import me.ahoo.wow.compiler.model.symbol.JavaBeans
import me.ahoo.wow.compiler.model.symbol.aliased
import me.ahoo.wow.compiler.model.symbol.isModuleInternal
import me.ahoo.wow.compiler.model.symbol.jsonIgnored
import me.ahoo.wow.compiler.model.symbol.jsonIgnoredAt
import me.ahoo.wow.compiler.model.symbol.jsonIgnoredProperties
import me.ahoo.wow.compiler.model.symbol.jsonNaming
import me.ahoo.wow.compiler.model.symbol.render
import me.ahoo.wow.compiler.model.symbol.sourceName
import me.ahoo.wow.compiler.model.symbol.wireName
import me.ahoo.wow.compiler.model.symbol.wireNameAt
import me.ahoo.wow.manifest.QueryFieldNames
import tools.jackson.databind.PropertyNamingStrategies

/**
 * The query fields of an aggregate's state: `<State>Properties`, an object extending
 * `wow-query-api`'s `SnapshotProperties` (the snapshot's system fields) with a `QueryField` per path of the state
 * a snapshot query can name, `state.<path>`. Generated only when the module has `wow-query-api` on its class path.
 *
 * A path follows what Jackson writes and the state schema of the Manifest describes: each public property, with a
 * backing field or computed (a getter alone, `val total: Int get() = ...`, which Jackson writes into the snapshot
 * too), under its `@JsonProperty` name or its name as the class's `@JsonNaming` translates it, without the `@JsonIgnore`d ones and those a class's `@JsonIgnoreProperties`
 * names. It descends into a property of an application class or interface, an interface's properties being those its
 * getters give, a default one included, as the schema describes them, and into the element class of whatever Jackson
 * writes as an array, decided by the type's hierarchy: a collection in any shape (a list, a set, a `SortedSet`, a
 * `Deque`, a class extending `ArrayList<Line>`), an object array, and a property declared as an `Iterable` (an
 * element's fields are named inside its `ELEMENT_MATCH`, which the DSL reads relative to the element). A `Sequence` of
 * a class is a problem naming the property: Jackson reads its elements back as maps. A map, an enum, a sealed
 * interface, an abstract or sealed class, a value class and the `kotlin.` and `java.` types are leaves, and so is a
 * class already on the path. Each constant is named after its path, as
 * `wow-manifest`'s [QueryFieldNames] names it, the same function the api module's generated `<Context>Contract` names
 * the state schema's paths with: the JSON property names in upper snake case, a nested one after
 * `__`: `STATE__ITEMS__PRODUCT_ID`. The object is `internal` when its state is (or a class it is nested in): its
 * constants are typed by the state's fields, which may be of the module's `internal` types.
 */
internal object QueryPropertiesSource {
    const val SNAPSHOT_PROPERTIES = "me.ahoo.wow.api.query.SnapshotProperties"
    private const val QUERY_FIELD = "me.ahoo.wow.api.query.QueryField"
    private const val ANY = "kotlin.Any?"
    private const val STATE = QueryFieldNames.STATE
    private const val ARRAY = "kotlin.Array"
    private const val SEQUENCE = "kotlin.sequences.Sequence"
    private const val ITERATOR = "iterator"

    /** A class that is one of these, or has one among its supertypes, is a collection Jackson writes as an array. */
    private val COLLECTIONS = setOf("kotlin.collections.Collection", "java.util.Collection")

    /**
     * The interfaces of a value Jackson writes as an array only when a property is declared as one: the list or the
     * sequence it holds. A class of the application implementing one is a bean, with properties of its own.
     */
    private val ITERATED = setOf(
        "kotlin.collections.Iterable",
        "kotlin.collections.MutableIterable",
        "java.lang.Iterable",
        SEQUENCE,
    )

    /** One generated constant: its [name], the logical [path] it holds and the Kotlin [type] of its values. */
    data class Property(val name: String, val path: String, val type: String = ANY)

    /** Whether the module has `wow-query-api` on its class path. */
    fun available(resolver: Resolver): Boolean =
        resolver.getClassDeclarationByName(resolver.getKSNameFromString(SNAPSHOT_PROPERTIES)) != null

    /**
     * Writes [state]'s `<State>Properties`, unless [generated], the states already written, holds it. The problems of
     * its paths' Jackson annotations, an argument without a value, are what it returns instead, writing nothing.
     *
     * An isolating output, whose origins are every file its paths are read from: the [root]'s, the state's, and those
     * of each class a path walks into, their supertypes included. So KSP writes it again whenever one of them changes,
     * not only when the state's own file does.
     */
    fun generate(
        codeGenerator: CodeGenerator,
        state: KSClassDeclaration,
        root: KSClassDeclaration,
        generated: MutableSet<String>,
    ): List<String> {
        if (!generated.add(state.qualifiedName!!.asString())) {
            return emptyList()
        }
        val problems = mutableListOf<String>()
        val origins = linkedSetOf<KSFile>()
        listOfNotNull(root.containingFile).forEach(origins::add)
        val properties = properties(state, problems, origins)
        if (problems.isNotEmpty()) {
            return problems.distinct()
        }
        codeGenerator.createNewFile(
            Dependencies(aggregating = false, sources = origins.toTypedArray()),
            state.packageName.asString(),
            className(state),
        ).bufferedWriter().use { it.write(render(state, properties)) }
        return emptyList()
    }

    /** The name of the object generated for [state]: its simple names joined, then `Properties`. */
    fun className(state: KSClassDeclaration): String =
        state.qualifiedName!!.asString().removePrefix("${state.packageName.asString()}.").replace(".", "") +
            "Properties"

    /**
     * The paths of [state] a query names, in declaration order, each once; their annotations' in [problems], and the
     * files of the classes they are read from in [origins].
     */
    fun properties(
        state: KSClassDeclaration,
        problems: MutableList<String>,
        origins: MutableSet<KSFile>,
    ): List<Property> {
        val properties = linkedMapOf<String, Property>()
        collect(state, STATE, listOf(state.qualifiedName!!.asString()), properties, problems, origins)
        return properties.values.toList()
    }

    fun render(state: KSClassDeclaration, properties: List<Property>): String = buildString {
        val packageName = state.packageName.asString()
        appendLine("// Generated by wow-compiler (ModelSymbolProcessor); do not edit.")
        if (packageName.isNotEmpty()) {
            appendLine("package ${KotlinNames.qualified(packageName)}")
        }
        appendLine()
        appendLine("/**")
        appendLine(" * The query fields of [${state.qualifiedName!!.asString()}] snapshots: the system fields of")
        appendLine(" * [$SNAPSHOT_PROPERTIES] and the state's own, `state.*`.")
        appendLine(" */")
        // As visible as its state: an internal state's fields may be of the module's internal types.
        val visibility = if (state.isModuleInternal()) "internal" else "public"
        appendLine(GeneratedMarks.CLASS)
        appendLine("$visibility object ${KotlinNames.quoted(className(state))} : $SNAPSHOT_PROPERTIES() {")
        properties.forEach {
            appendLine("    public val ${it.name}: $QUERY_FIELD<${it.type}> = $QUERY_FIELD.of(\"${it.path}\")")
        }
        appendLine("}")
    }

    private fun collect(
        owner: KSClassDeclaration,
        path: String,
        visited: List<String>,
        properties: MutableMap<String, Property>,
        problems: MutableList<String>,
        origins: MutableSet<KSFile>,
    ) {
        // Its own properties and its supertypes', each of a file of its own.
        (sequenceOf(owner) + owner.getAllSuperTypes().map { it.declaration })
            .mapNotNull { it.containingFile }.forEach(origins::add)
        val ignored = owner.jsonIgnoredProperties(problems)
        val naming = owner.jsonNaming(problems)
        val members = fieldsOf(owner, ignored, naming, problems) + gettersOf(owner, ignored, naming, problems)
        members.forEach { (wireName, type) ->
            if (!QueryFieldNames.isSegment(wireName)) return@forEach
            val childPath = "$path.$wireName"
            val childName = QueryFieldNames.constantOf(childPath)
            if (properties.putIfAbsent(childName, Property(childName, childPath, type.kotlinType())) != null) {
                return@forEach
            }
            val nested = type.nestedClass(owner, wireName, problems) ?: return@forEach
            val qualified = nested.qualifiedName!!.asString()
            if (qualified !in visited) {
                collect(nested, childPath, visited + qualified, properties, problems, origins)
            }
        }
    }

    /**
     * The properties of [owner] Jackson writes, each under its name, with its type. A Java bean's private field is
     * written through its public getter, whose annotations count too (#6839).
     */
    private fun fieldsOf(
        owner: KSClassDeclaration,
        ignored: Set<String>,
        naming: PropertyNamingStrategies.NamingBase?,
        problems: MutableList<String>,
    ): List<Pair<String, KSType>> = owner.getAllProperties().mapNotNull { property ->
        val getter = JavaBeans.getterOf(property, owner)
        val wireName = if (getter == null) {
            property.wireName(problems, naming)
        } else {
            wireNameAt(property.simpleName.asString(), listOf(property, getter), problems, naming)
        }
        val getterIgnored = getter != null && jsonIgnoredAt(listOf(getter), problems)
        (wireName to property.type.resolve())
            .takeIf { property.isQueryable(getter != null, wireName, ignored, problems) && !getterIgnored }
    }.toList()

    /** The getters of a Java bean [owner] that read no field of their name, which Jackson writes all the same. */
    private fun gettersOf(
        owner: KSClassDeclaration,
        ignored: Set<String>,
        naming: PropertyNamingStrategies.NamingBase?,
        problems: MutableList<String>,
    ): List<Pair<String, KSType>> {
        val fields = owner.getAllProperties().mapTo(HashSet()) { it.simpleName.asString() }
        return JavaBeans.computed(owner, fields).mapNotNull { (name, getter) ->
            val wireName = wireNameAt(name, listOf(getter), problems, naming)
            (wireName to getter.returnType!!.resolve())
                .takeIf { name !in ignored && wireName !in ignored && !jsonIgnoredAt(listOf(getter), problems) }
        }
    }

    /**
     * This type as Kotlin code, the values a constant of it holds: its qualified name, its type arguments and its
     * nullability; a platform type of a Java state is nullable, and a type parameter is [ANY].
     */
    private fun KSType.kotlinType(): String {
        val name = declaration.sourceName()
            ?.takeUnless { declaration is KSTypeParameter }
            ?: return ANY
        val arguments = if (arguments.isEmpty()) {
            ""
        } else {
            arguments.joinToString(", ", "<", ">") { argument ->
                val type = argument.type?.resolve() ?: return@joinToString "*"
                when (argument.variance) {
                    Variance.STAR -> "*"
                    Variance.COVARIANT -> "out ${type.kotlinType()}"
                    Variance.CONTRAVARIANT -> "in ${type.kotlinType()}"
                    Variance.INVARIANT -> type.kotlinType()
                }
            }
        }
        return name + arguments + if (nullability == Nullability.NOT_NULL) "" else "?"
    }

    /** Whether Jackson writes this property: public, or a Java field a public getter reads ([read]), and not ignored. */
    private fun KSPropertyDeclaration.isQueryable(
        read: Boolean,
        wireName: String,
        ignored: Set<String>,
        problems: MutableList<String>,
    ): Boolean = (isPublic() || read) && extensionReceiver == null &&
        simpleName.asString() !in ignored && wireName !in ignored &&
        !jsonIgnored(problems)

    /**
     * The class whose properties a path continues into past [owner]'s property [wireName] of this type: the type
     * itself, or the element type of a collection, an array or a declared iterable. A sequence's element class, which
     * Jackson reads back as maps, is a problem to [problems] instead.
     */
    private fun KSType.nestedClass(
        owner: KSClassDeclaration,
        wireName: String,
        problems: MutableList<String>,
    ): KSClassDeclaration? {
        (declaration as? KSTypeAlias)?.let { return aliased(it).nestedClass(owner, wireName, problems) }
        val type = declaration as? KSClassDeclaration ?: return null
        // A public property's type is never local: Kotlin approximates a local type to a supertype that has a name.
        val qualified = type.qualifiedName!!.asString()
        val element = elementOf(type, qualified) ?: return type.nestedClassOf(qualified)
        val nested = element.nestedClass(owner, wireName, problems)
        if (nested != null && qualified == SEQUENCE) {
            problems += "${owner.qualifiedName!!.asString()}.$wireName is a Sequence of " +
                "${nested.qualifiedName!!.asString()}: Jackson writes a Sequence as an array but reads its elements " +
                "back as maps, so a state holds none of these, and its query fields could name none of their " +
                "fields; hold them in a List or a Set"
            return null
        }
        return nested
    }

    /**
     * The element type of this type of the class [type], named [qualified], when Jackson writes it as an array: an
     * object array, a collection (a class whose supertypes reach `Collection`: a `SortedSet`, an `ArrayDeque`, a class
     * extending `ArrayList<Line>`), or an [ITERATED] interface; null for any other type. The element type is the one
     * its `iterator()` yields as a member of this type, a supertype's type argument substituted.
     */
    private fun KSType.elementOf(type: KSClassDeclaration, qualified: String): KSType? {
        // Each has one type parameter, a star projection of which has no type.
        if (qualified == ARRAY || qualified in ITERATED) {
            return arguments.single().type?.resolve()
        }
        val collection = qualified in COLLECTIONS ||
            type.getAllSuperTypes().any { it.declaration.qualifiedName!!.asString() in COLLECTIONS }
        if (!collection) return null
        // Every collection has `iterator()`, which a collection's class declares or inherits.
        val iterator = type.getAllFunctions().first { it.simpleName.asString() == ITERATOR }
        return iterator.asMemberOf(makeNotNullable()).returnType!!.arguments.single().type?.resolve()
    }

    /** This class, [qualified], when a path continues into its properties: an application's class not a leaf. */
    private fun KSClassDeclaration.nestedClassOf(qualified: String): KSClassDeclaration? {
        val platform = qualified.startsWith("kotlin.") || qualified.startsWith("java.") ||
            qualified.startsWith("javax.")
        return if (platform || isLeaf()) null else this
    }

    /** Whether no path continues into this class: it is no class or plain interface whose properties are its own. */
    private fun KSClassDeclaration.isLeaf(): Boolean = when (classKind) {
        ClassKind.CLASS -> modifiers.any {
            it == Modifier.ABSTRACT || it == Modifier.SEALED || it == Modifier.VALUE || it == Modifier.INLINE
        }
        // A sealed interface is its subtypes' alternatives, each with properties of its own.
        ClassKind.INTERFACE -> Modifier.SEALED in modifiers
        else -> true
    }
}
