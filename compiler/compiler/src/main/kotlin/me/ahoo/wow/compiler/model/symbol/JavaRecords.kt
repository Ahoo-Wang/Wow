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

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Origin

/**
 * A Java record's components, as KSP shows them: no property, only the canonical constructor's parameters and the
 * accessors. A component's annotation is read where KSP shows it: on the constructor's parameter of a record compiled
 * here, and on the accessor of one compiled in a library, where javac carries a `FUNCTION`-targeted annotation
 * (`@AggregateId`).
 */
internal object JavaRecords {
    private const val JAVA_LANG = "java.lang"
    private const val RECORD = "Record"
    private const val CONSTRUCTOR = "<init>"

    /**
     * A record component: its [name], the qualified name of its [type] (null when KSP resolves none), and the [sites]
     * its annotations are read from.
     */
    data class Component(val name: String, val type: String?, val sites: List<KSAnnotated>)

    fun isRecord(type: KSClassDeclaration): Boolean =
        type.origin in JAVA && type.getAllSuperTypes().any { it.declaration.isRecordClass() }

    private fun KSDeclaration.isRecordClass(): Boolean =
        packageName.asString() == JAVA_LANG && simpleName.asString() == RECORD

    /** The components of [record]: the canonical constructor's parameters that have an accessor of their name. */
    fun components(record: KSClassDeclaration): List<Component> {
        if (!isRecord(record)) return emptyList()
        val (constructors, methods) = record.getDeclaredFunctions()
            .partition { it.simpleName.asString() == CONSTRUCTOR }
        val accessors = methods.filter { it.parameters.isEmpty() }.associateBy { it.simpleName.asString() }
        // The canonical constructor: every parameter a component, of the most parameters; another constructor of a
        // record has a parameter of no component, or fewer. A record always has one.
        return constructors.mapNotNull { componentsOf(it, accessors) }.maxBy { it.size }
    }

    /** The components [constructor]'s parameters are, each read by the accessor of its name; null when one has none. */
    private fun componentsOf(
        constructor: KSFunctionDeclaration,
        accessors: Map<String, KSFunctionDeclaration>,
    ): List<Component>? = constructor.parameters.map { parameter ->
        val accessor = accessors[parameter.name?.asString()] ?: return null
        val type = accessor.returnType?.resolve()?.declaration?.qualifiedName?.asString()
        Component(accessor.simpleName.asString(), type, listOf(parameter, accessor))
    }

    /** The component of [record] named [name] whose type is [type], by qualified name; null when it has none. */
    fun component(record: KSClassDeclaration, name: String, type: Set<String>): Component? =
        components(record).firstOrNull { it.name == name && it.type in type }

    /** Whether [function] of [record] is a site of its components: the canonical constructor, or an accessor. */
    fun isComponentSite(record: KSClassDeclaration, function: KSFunctionDeclaration): Boolean {
        val names = components(record).map { it.name }
        val name = function.simpleName.asString()
        return if (name == CONSTRUCTOR) {
            function.parameters.map { it.name?.asString() }.any { it in names }
        } else {
            function.parameters.isEmpty() && name in names
        }
    }

    /** The origins of a Java declaration, compiled here or in a library. */
    val JAVA: Set<Origin> = setOf(Origin.JAVA, Origin.JAVA_LIB)
}
