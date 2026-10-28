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

package me.ahoo.wow.tck.values

import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.JsonNode
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.regex.Pattern

/**
 * The guard of the hand-written `equals` of a module's classes: a class that is not a data class and
 * overrides `equals` lists the fields it compares by hand (`valueFields()`, or comparisons of its own), and a field
 * added later and left out is not caught by the compiler. This spec builds an instance of each such class with every
 * field set (bypassing its constructor), checks that an instance with the same fields is equal to it and has its hash
 * code, and that changing any one field makes it unequal: a field `equals` does not compare fails the class, unless it
 * is [excluded] with its reason.
 *
 * A module's test extends it and names the [packages] it checks; the classes are those compiled in the module itself
 * (its `build/classes`), so each module checks its own. Fields of the type `kotlin.Lazy` (a `by lazy` cache), static,
 * synthetic and transient fields are not values and are left out.
 */
abstract class ValueSemanticsSpec {
    /** The packages, with their subpackages, whose classes compiled in this module are checked. */
    protected abstract val packages: List<String>

    /**
     * Fields `equals` leaves out on purpose, `binary.Class#field` to the reason; `package.*#field` names the field of
     * every class of the package and its subpackages, such as a constant each class sets.
     */
    protected open val excluded: Map<String, String> = emptyMap()

    /** Classes whose `equals` is not value equality on purpose (a filter, an identity), binary name to the reason. */
    protected open val excludedClasses: Map<String, String> = emptyMap()

    @TestFactory
    fun `every hand-written equals compares every field`(): List<DynamicTest> =
        ValueSemantics.classesIn(packages, javaClass).filterNot { it.name in excludedClasses }.map { type ->
            DynamicTest.dynamicTest(type.name) { ValueSemantics.check(type, excluded) }
        }

    /**
     * A class without a `copy` (its fields may grow) is copied through a `copyWith` whose parameters default to the
     * receiver's fields: one that misses a field rebuilds the class with that field's default instead, and a copy with
     * every default must equal the original.
     */
    @TestFactory
    fun `every copyWith keeps every field`(): List<DynamicTest> =
        ValueSemantics.copiersIn(packages, javaClass).filterKeys { it.name !in excludedClasses }.map { (type, copier) ->
            DynamicTest.dynamicTest(type.name) { ValueSemantics.checkCopy(type, copier, excluded) }
        }

    @Test
    fun `the module has classes to check, and every exclusion names a field of one`() {
        val classes = ValueSemantics.classesIn(packages, javaClass)
        check(classes.isNotEmpty()) { "No class of $packages overrides equals here: the packages are wrong." }
        val fields = classes.flatMap { type -> ValueSemantics.fieldsOf(type).map { "${type.name}#${it.name}" } }.toSet()
        val stale = excluded.keys.filterNot { key -> fields.any { ValueSemantics.matches(key, it) } } +
            (excludedClasses.keys - classes.map { it.name }.toSet())
        check(stale.isEmpty()) { "Exclusions name no field or class checked here: $stale" }
    }
}

/** How [ValueSemanticsSpec] finds the classes, builds their instances and checks them. */
internal object ValueSemantics {
    private val unsafe: Any = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        .apply { isAccessible = true }.get(null)
    private val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
    private const val MAX_DEPTH = 6

    /** Whether the exclusion [key] names [field] (`binary.Class#field`): exactly, or by `package.*#field`. */
    fun matches(key: String, field: String): Boolean {
        if (key == field) return true
        val (owner, name) = key.split('#', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return owner.endsWith(".*") && field.substringAfter('#') == name &&
            field.substringBefore('#').startsWith(owner.removeSuffix("*"))
    }

    /**
     * The classes of [packages] compiled in the module of [spec] (its `build/classes`, found from where the spec's
     * class was loaded) that override `equals`, data classes and records left out.
     */
    fun classesIn(packages: List<String>, spec: Class<*>): List<Class<*>> = compiledIn(
        packages,
        spec
    ).filter(::isChecked)

    /**
     * The `copyWith` of each class of [packages] compiled in the module of [spec] that has one: the static method a
     * member or an extension `copyWith` with default arguments compiles to (`copyWith${'$'}default`), by its receiver.
     * An interface's or an abstract class's has no instance of its own to copy and is left to its implementations.
     */
    fun copiersIn(packages: List<String>, spec: Class<*>): Map<Class<*>, java.lang.reflect.Method> =
        compiledIn(packages, spec).flatMap { it.declaredMethods.asList() }
            .filter { Modifier.isStatic(it.modifiers) && it.name == "copyWith${'$'}default" && it.parameterCount > 0 }
            .filterNot { it.parameterTypes[0].isInterface || Modifier.isAbstract(it.parameterTypes[0].modifiers) }
            .associateBy { it.parameterTypes[0] }

    /** Checks that [copier] with every default copies an instance of [type] whose fields are all set into an equal one. */
    fun checkCopy(type: Class<*>, copier: java.lang.reflect.Method, excluded: Map<String, String>) {
        val fields = fieldsOf(type).filterNot { it.type == Lazy::class.java }
        val values = fields.associateWith { field ->
            valueOf(field.type, field.genericType, 1, 0, emptySet()).takeUnless { it === Unbuildable }
        }
        val instance = instanceOf(type, values)
        copier.isAccessible = true
        // Receiver, the parameters (each left to its default), the default masks (every bit set), the marker.
        val types = copier.parameterTypes
        val masks = types.drop(1).dropLast(1).takeLastWhile { it == Int::class.javaPrimitiveType }.size
        val parameters = types.size - 2 - masks
        val arguments = arrayOfNulls<Any>(types.size)
        arguments[0] = instance
        for (i in 1..parameters) arguments[i] = defaultOf(types[i])
        for (i in 1..masks) arguments[parameters + i] = -1
        val copy = try {
            copier.invoke(null, *arguments)
        } catch (refused: java.lang.reflect.InvocationTargetException) {
            // A constructor that checks its fields together refuses fields generated one by one: skipped, saying so.
            val cause = refused.cause
            if (cause is IllegalArgumentException || cause is IllegalStateException) {
                org.junit.jupiter.api.Assumptions.abort<Unit>(
                    "${type.name}: its constructor refuses the generated fields (${cause.message}); its copyWith is " +
                        "not checked here",
                )
            }
            throw refused
        }
        val lost = fields.filterNot { field -> excluded.keys.any { matches(it, "${type.name}#${field.name}") } }
            .filter { field -> field.get(copy) != field.get(instance) }
        check(lost.isEmpty() && copy == instance) {
            "${type.name}: copyWith does not carry ${lost.joinToString { it.name }}; give it a parameter defaulting to " +
                "the receiver's, or exclude the field with its reason"
        }
    }

    private fun defaultOf(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Char::class.javaPrimitiveType -> 0.toChar()
        Byte::class.javaPrimitiveType -> 0.toByte()
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        else -> null
    }

    /** The classes of [packages] compiled in the module of [spec] (its `build/classes`, or its jar in `build/libs`). */
    private fun compiledIn(packages: List<String>, spec: Class<*>): List<Class<*>> {
        val loader = Thread.currentThread().contextClassLoader
        val location = File(spec.protectionDomain.codeSource.location.toURI())
        // build/classes/kotlin/test: the module's own classes are under its build directory, as class directories or,
        // with test fixtures, as its jar in build/libs.
        val build = generateSequence(location) { it.parentFile }.firstOrNull { it.name == "build" } ?: location
        return packages.flatMap { pkg ->
            val path = pkg.replace('.', '/')
            loader.getResources(path).toList().flatMap { url ->
                when (url.protocol) {
                    "file" -> File(url.toURI()).takeIf { it.absolutePath.startsWith(build.absolutePath) }
                        ?.let { root ->
                            val base = root.absolutePath.removeSuffix(path).trimEnd('/')
                            root.walkTopDown().filter { it.isFile && it.extension == "class" }
                                .map { it.absolutePath.removePrefix("$base/") }.toList()
                        }.orEmpty()

                    "jar" -> {
                        val jar = File(java.net.URI(url.path.substringBefore("!/")))
                        if (!jar.absolutePath.startsWith(build.absolutePath)) {
                            emptyList()
                        } else {
                            java.util.jar.JarFile(jar).use { file ->
                                file.entries().toList().map { it.name }
                                    .filter { it.startsWith("$path/") && it.endsWith(".class") }
                            }
                        }
                    }

                    else -> emptyList()
                }
            }
        }.map { it.removeSuffix(".class").replace('/', '.') }.distinct().sorted()
            .mapNotNull { name -> runCatching { Class.forName(name, false, loader) }.getOrNull() }
    }

    private fun isChecked(type: Class<*>): Boolean {
        val notAValue = listOf(
            type.isInterface,
            type.isEnum,
            type.isAnnotation,
            type.isSynthetic,
            type.isAnonymousClass,
            type.isLocalClass,
            type.isRecord,
            Modifier.isAbstract(type.modifiers),
        )
        if (notAValue.any { it }) {
            return false
        }
        val overrides = runCatching { type.getDeclaredMethod("equals", Any::class.java) }.isSuccess
        val dataClass = type.declaredMethods.any { it.name == "component1" } &&
            type.declaredMethods.any { it.name == "copy" }
        val singleton = runCatching { type.getDeclaredField("INSTANCE") }.isSuccess
        return overrides && !dataClass && !singleton
    }

    /** The fields of [type] and its superclasses that are values. */
    fun fieldsOf(type: Class<*>): List<Field> = generateSequence(type) { it.superclass }
        .takeWhile { it != Any::class.java }
        .flatMap { it.declaredFields.asSequence() }
        .filterNot { Modifier.isStatic(it.modifiers) || Modifier.isTransient(it.modifiers) || it.isSynthetic }
        .onEach { it.isAccessible = true }
        .toList()

    fun check(type: Class<*>, excluded: Map<String, String>) {
        val all = fieldsOf(type)
        // A `by lazy` cache holds a value, not one equals compares: it is set once, the same for both instances.
        val caches = all.filter { it.type == Lazy::class.java }
        val fields = all - caches.toSet()
        val isExcluded = { field: Field -> excluded.keys.any { matches(it, "${type.name}#${field.name}") } }
        val first = fields.associateWith { field -> valueOf(field.type, field.genericType, 1, 0, emptySet()) }
        val unbuilt = first.filterValues { it === Unbuildable }.keys.filterNot(isExcluded)
        check(unbuilt.isEmpty()) {
            "${type.name}: no value of ${unbuilt.joinToString { "${it.name}: ${it.genericType.typeName}" }} can be " +
                "built; exclude it with its reason"
        }
        val built = first.mapValues { (_, value) -> value.takeUnless { it === Unbuildable } } +
            caches.associateWith { cache -> lazyOf(lazyValue(cache.genericType)) }
        val instance = instanceOf(type, built)
        val same = instanceOf(type, built)
        check(instance == same) { "${type.name}: two instances with the same fields are not equal" }
        check(instance.hashCode() == same.hashCode()) { "${type.name}: equal instances have different hash codes" }
        val missed = fields.filterNot(isExcluded).filter { field ->
            val other = valueOf(field.type, field.genericType, 2, 0, emptySet())
            if (other === Unbuildable || other == built[field]) {
                false
            } else {
                instance == instanceOf(type, built + (field to other))
            }
        }
        check(missed.isEmpty()) {
            "${type.name}: equals does not compare ${missed.joinToString { it.name }}; compare it, or exclude it " +
                "with its reason"
        }
    }

    /** A value of what the `Lazy` of [generic] holds, for a cache equals reads through. */
    private fun lazyValue(generic: Type): Any? {
        val held = (generic as? ParameterizedType)?.actualTypeArguments?.firstOrNull()
        val raw = rawOf(held) ?: return null
        return valueOf(raw, held!!, 1, 0, emptySet()).takeUnless { it === Unbuildable }
    }

    private fun instanceOf(type: Class<*>, values: Map<Field, Any?>): Any =
        allocate.invoke(
            unsafe,
            type
        ).also { instance -> values.forEach { (field, value) -> field.set(instance, value) } }

    private object Unbuildable

    /** A value of [type] for [seed] (1 or 2, two different values), or [Unbuildable]. */
    private fun valueOf(type: Class<*>, generic: Type, seed: Int, depth: Int, building: Set<Class<*>>): Any? {
        primitive(type, seed)?.let { return it }
        known(type, seed)?.let { return it }
        collection(type, generic, seed, depth, building)?.let { return it }
        boxed(type, seed)?.let { return it }
        if (type.isSealed) {
            val permitted = type.permittedSubclasses
            val chosen = permitted[(seed - 1) % permitted.size]
            return valueOf(chosen, chosen, seed, depth + 1, building)
        }
        if (type.isInterface) {
            return proxyOf(type, seed)
        }
        runCatching { type.getDeclaredField("INSTANCE").get(null) }.getOrNull()?.let { return it }
        return filled(type, seed, depth, building)
    }

    /** The JDK's, Kotlin's and Jackson's types a field may hold, built directly. */
    @Suppress("CyclomaticComplexMethod")
    private fun known(type: Class<*>, seed: Int): Any? = when {
        type == String::class.java || type == CharSequence::class.java || type == Any::class.java -> "v$seed"
        type.isEnum -> type.enumConstants.let { it[(seed - 1) % it.size] }
        type == Class::class.java -> if (seed == 1) String::class.java else Int::class.java
        type == BigDecimal::class.java || type == Number::class.java -> BigDecimal(seed)
        type == BigInteger::class.java -> BigInteger.valueOf(seed.toLong())
        type == Instant::class.java -> Instant.ofEpochMilli(seed.toLong())
        type == java.time.Duration::class.java -> java.time.Duration.ofMillis(seed.toLong())
        type == UUID::class.java -> UUID(0, seed.toLong())
        type == URI::class.java -> URI.create("urn:v$seed")
        type == Pattern::class.java -> Pattern.compile("p$seed")
        type == Regex::class.java -> Regex("r$seed")
        type == java.util.BitSet::class.java -> java.util.BitSet().apply { set(seed) }
        type == java.time.format.DateTimeFormatter::class.java ->
            java.time.format.DateTimeFormatter.ofPattern(if (seed == 1) "yyyy-MM-dd" else "dd/MM/yyyy")

        type == java.time.ZoneId::class.java -> java.time.ZoneId.of(if (seed == 1) "UTC" else "Asia/Shanghai")
        JsonNode::class.java.isAssignableFrom(type) -> WowJson.mapper.readTree("{\"v\":$seed}")
        type.isAssignableFrom(IllegalStateException::class.java) -> IllegalStateException("v$seed")
        else -> null
    }

    /** An array, a map, a set, a list or a pair of [type], one element of [seed]; null for another type. */
    private fun collection(type: Class<*>, generic: Type, seed: Int, depth: Int, building: Set<Class<*>>): Any? {
        fun element(index: Int) = argument(generic, index, seed, depth, building)
        return when {
            type.isArray -> {
                val element = valueOf(type.componentType, type.componentType, seed, depth + 1, building)
                java.lang.reflect.Array.newInstance(type.componentType, 1)
                    .also { if (element !== Unbuildable) java.lang.reflect.Array.set(it, 0, element) }
            }

            type == Any::class.java -> null
            type.isAssignableFrom(LinkedHashMap::class.java) -> linkedMapOf(element(0) to element(1))
            type.isAssignableFrom(LinkedHashSet::class.java) -> linkedSetOf(element(0))
            type.isAssignableFrom(ArrayList::class.java) -> arrayListOf(element(0))
            type == Pair::class.java -> Pair(element(0), element(1))
            else -> null
        }
    }

    /** An implementation of the interface [type] equal to itself only. */
    private fun proxyOf(type: Class<*>, seed: Int): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, arguments ->
            when (method.name) {
                "equals" -> proxy === arguments?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "${type.simpleName}#$seed"
                else -> primitive(method.returnType, 0)
            }
        }

    /** An instance of the concrete class [type] with each field set, or [Unbuildable]. */
    private fun filled(type: Class<*>, seed: Int, depth: Int, building: Set<Class<*>>): Any? {
        val unbuildable = listOf(
            Modifier.isAbstract(type.modifiers),
            type.name.startsWith("java."),
            type in building,
            depth >= MAX_DEPTH,
        )
        if (unbuildable.any { it }) {
            return Unbuildable
        }
        val instance = allocate.invoke(unsafe, type)
        for (field in fieldsOf(type)) {
            val value = valueOf(field.type, field.genericType, seed, depth + 1, building + type)
            if (value !== Unbuildable) field.set(instance, value)
        }
        return instance
    }

    private fun argument(generic: Type, index: Int, seed: Int, depth: Int, building: Set<Class<*>>): Any? {
        val argument = (generic as? ParameterizedType)?.actualTypeArguments?.getOrNull(index)
        val raw = rawOf(argument) ?: return "e$seed"
        return valueOf(raw, argument!!, seed, depth + 1, building).takeUnless { it === Unbuildable } ?: "e$seed"
    }

    private fun rawOf(type: Type?): Class<*>? = when (type) {
        is Class<*> -> type
        is ParameterizedType -> type.rawType as Class<*>
        is WildcardType -> rawOf(type.upperBounds.firstOrNull())
        else -> null
    }

    /** A Kotlin value class boxed: its `box-impl` of a value of what it holds. */
    private fun boxed(type: Class<*>, seed: Int): Any? {
        val box = type.declaredMethods.firstOrNull { it.name == "box-impl" && it.parameterCount == 1 } ?: return null
        val held = primitive(box.parameterTypes[0], seed) ?: valueOf(
            box.parameterTypes[0],
            box.parameterTypes[0],
            seed,
            MAX_DEPTH - 1,
            emptySet(),
        )
        return box.invoke(null, held)
    }

    private fun primitive(type: Class<*>, seed: Int): Any? = when (type) {
        Int::class.javaPrimitiveType, Int::class.javaObjectType -> seed
        Long::class.javaPrimitiveType, Long::class.javaObjectType -> seed.toLong()
        Short::class.javaPrimitiveType, Short::class.javaObjectType -> seed.toShort()
        Byte::class.javaPrimitiveType, Byte::class.javaObjectType -> seed.toByte()
        Double::class.javaPrimitiveType, Double::class.javaObjectType -> seed.toDouble()
        Float::class.javaPrimitiveType, Float::class.javaObjectType -> seed.toFloat()
        Char::class.javaPrimitiveType, Char::class.javaObjectType -> 'a' + seed
        Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> seed % 2 == 0
        else -> null
    }
}
