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

package me.ahoo.wow.tck.architecture

import me.ahoo.wow.infra.Decorator
import java.io.File
import java.lang.reflect.Modifier
import java.net.JarURLConnection
import java.util.jar.JarFile
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty
import kotlin.reflect.KVisibility
import kotlin.reflect.full.allSuperclasses
import kotlin.reflect.full.declaredMembers
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.superclasses
import kotlin.reflect.jvm.jvmErasure

/**
 * Contract for hand-written decorators and storage implementations over Wow SPI interfaces.
 *
 * An SPI method with a default body is a fallback, for example `EventStore.existsRequestId` scans the whole
 * stream. A decorator that inherits the fallback instead of forwarding the call silently bypasses the
 * delegate's own (cheaper) implementation. [inheritedDefaults] lists every such inherited default so a test can
 * require each decorator to override all of them.
 */
object DefaultMethodContract {
    private const val SPI_PACKAGE_PREFIX = "me.ahoo.wow."

    /**
     * Default members that return a constant classifier of the interface itself (the delegate returns the same
     * value), so a decorator inherits them by design.
     */
    val CONSTANT_CLASSIFIERS: Set<String> = setOf("topicKind")

    /**
     * Deprecated defaults that are compatibility adapters onto their replacement member (`Interface.member`), each
     * marked `compat(...)` in the source and listed in docs/compat-debt.md. A decorator that forwards the replacement
     * forwards them too, so they are not checked. Any other deprecated default still is: deprecation alone does not
     * make inheriting a fallback correct.
     */
    val COMPAT_ADAPTERS: Set<String> = setOf(
        // compat(wow<9.3): the 9.2 receive entry, an adapter onto receiver; see docs/compat-debt.md.
        "MessageBus.receive",
    )

    /**
     * Returns `SimpleName.member(ParamTypes)` for every member with a default body, declared by a Wow interface
     * (package `me.ahoo.wow.`), that [type] inherits without overriding it in a class of its own hierarchy.
     *
     * An interface that itself extends [Decorator] (a decorator mixin such as `TracingMessageBus`) is part of the
     * implementation, not of the SPI: its default members count as overrides, and its own defaults are not checked.
     *
     * A deprecated default listed in [COMPAT_ADAPTERS] is skipped: it is a compatibility adapter onto its replacement
     * member (`MessageBus.receive` onto `receiver`), so a decorator that forwards the replacement forwards it too.
     *
     * Kotlin reflection is used on purpose: the compiler emits a JVM bridge for each inherited default
     * (`invokespecial Interface.member`), so Java reflection cannot tell an override from an inherited default.
     *
     * @param type the decorator or implementation class to check.
     * @param ignoredMembers member names that are constant classifiers rather than delegated behaviour, for example
     * `topicKind`; inheriting them is correct by design.
     */
    fun inheritedDefaults(type: Class<*>, ignoredMembers: Set<String> = CONSTANT_CLASSIFIERS): List<String> {
        val kType = type.kotlin
        val classHierarchy = generateSequence(kType) { current ->
            current.superclasses.firstOrNull { it.java.isInterface.not() && it != Any::class }
        }.toList()
        val (decoratorMixins, spis) = kType.allSuperclasses
            .filter { it.java.isInterface && it.java.name.startsWith(SPI_PACKAGE_PREFIX) }
            .partition { Decorator::class.java.isAssignableFrom(it.java) }
        val overridden = (classHierarchy + decoratorMixins).flatMap { it.declaredMembers }
        return spis
            .asSequence()
            .flatMap { spi -> spi.declaredMembers.asSequence().map { spi to it } }
            .filter { (_, it) -> !it.isAbstract && it.visibility == KVisibility.PUBLIC && it.name !in ignoredMembers }
            .filterNot { (spi, it) -> it.findAnnotation<Deprecated>() != null && "${spi.simpleName}.${it.name}" in COMPAT_ADAPTERS }
            .map { (_, it) -> it }
            .filter { default -> overridden.none { it.overrides(default) } }
            .map { "${type.simpleName}.${it.signature()}" }
            .distinct()
            .sorted()
            .toList()
    }

    /**
     * Returns the inherited defaults of all [types], see [inheritedDefaults].
     */
    fun inheritedDefaults(
        types: Collection<Class<*>>,
        ignoredMembers: Set<String> = CONSTANT_CLASSIFIERS
    ): List<String> =
        types.flatMap { inheritedDefaults(it, ignoredMembers) }.sorted()

    /**
     * Fails when the inherited defaults of [types] differ from [knownGaps] (key = `SimpleName.method(ParamTypes)`,
     * value = the work item that removes the gap). A new gap fails the check, and so does a fixed gap that is still
     * listed, so the known-gap list can only shrink.
     */
    fun assertOnlyKnownGaps(
        types: Collection<Class<*>>,
        knownGaps: Map<String, String>,
        ignoredMembers: Set<String> = CONSTANT_CLASSIFIERS,
    ) {
        val actual = inheritedDefaults(types, ignoredMembers).toSet()
        val unexpected = actual - knownGaps.keys
        val fixed = knownGaps.keys - actual
        check(unexpected.isEmpty() && fixed.isEmpty()) {
            buildString {
                if (unexpected.isNotEmpty()) {
                    appendLine("These classes inherit an SPI default method instead of overriding (forwarding) it:")
                    unexpected.sorted().forEach { appendLine("  - $it") }
                }
                if (fixed.isNotEmpty()) {
                    appendLine("These known gaps are fixed; remove them from the known-gap list:")
                    fixed.sorted().forEach { appendLine("  - $it (${knownGaps[it]})") }
                }
            }
        }
    }

    /**
     * Lists the concrete classes under [packageName] (including sub-packages) that are assignable to [type],
     * loaded through [classLoader]. Supports directory and jar classpath entries.
     */
    fun concreteSubtypes(
        packageName: String,
        type: Class<*>,
        classLoader: ClassLoader = type.classLoader,
    ): List<Class<*>> =
        classNamesUnder(packageName, classLoader)
            .map { Class.forName(it, false, classLoader) }
            .filter {
                type.isAssignableFrom(it) &&
                    !it.isInterface &&
                    !Modifier.isAbstract(it.modifiers) &&
                    !it.isAnonymousClass &&
                    !it.isSynthetic
            }
            .sortedBy { it.name }

    private fun KCallable<*>.valueParameterTypes(): List<KClass<*>> =
        parameters.filter { it.kind == KParameter.Kind.VALUE }.map { it.type.jvmErasure }

    private fun KCallable<*>.overrides(default: KCallable<*>): Boolean {
        if (name != default.name || (this is KProperty<*>) != (default is KProperty<*>)) {
            return false
        }
        val own = valueParameterTypes()
        val inherited = default.valueParameterTypes()
        return own.size == inherited.size &&
            own.zip(inherited).all { (ownType, inheritedType) -> ownType.isSubclassOf(inheritedType) }
    }

    private fun KCallable<*>.signature(): String =
        if (this is KProperty<*>) {
            name
        } else {
            "$name(${valueParameterTypes().joinToString(",") { it.simpleName.orEmpty() }})"
        }

    private fun classNamesUnder(packageName: String, classLoader: ClassLoader): Set<String> {
        val path = packageName.replace('.', '/')
        val names = sortedSetOf<String>()
        classLoader.getResources(path).asSequence().forEach { url ->
            when (url.protocol) {
                "file" -> {
                    val root = File(url.toURI())
                    root.walkTopDown()
                        .filter { it.isFile && it.name.endsWith(".class") }
                        .forEach { file ->
                            val relative = file.relativeTo(root).invariantSeparatorsPath.removeSuffix(".class")
                            names.add("$packageName.${relative.replace('/', '.')}")
                        }
                }

                "jar" -> {
                    val connection = url.openConnection() as JarURLConnection
                    connection.useCaches = false
                    connection.jarFile.use { jar: JarFile ->
                        jar.entries().asSequence()
                            .filter { it.name.startsWith("$path/") && it.name.endsWith(".class") }
                            .forEach { names.add(it.name.removeSuffix(".class").replace('/', '.')) }
                    }
                }
            }
        }
        return names.filterNot { it.endsWith("module-info") || it.endsWith("package-info") }.toSet()
    }
}
