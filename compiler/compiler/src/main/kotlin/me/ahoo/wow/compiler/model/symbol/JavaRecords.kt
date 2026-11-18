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
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Origin

/**
 * A Java record's components, as KSP shows them: no property, only the canonical constructor's parameters and the
 * accessors. A component's annotation is read where KSP shows it: on the constructor's parameter of a record compiled
 * here, and on the accessor of one compiled in a library, where javac carries a `FUNCTION`-targeted annotation
 * (`@AggregateId`).
 */
internal object JavaRecords {
    private const val RECORD = "java.lang.Record"
    private const val CONSTRUCTOR = "<init>"

    /** A record component: its [name], its [type], and the [sites] its annotations are read from. */
    data class Component(val name: String, val type: KSType?, val sites: List<KSAnnotated>)

    fun isRecord(type: KSClassDeclaration): Boolean =
        type.origin in JAVA && type.getAllSuperTypes().any { it.declaration.qualifiedName?.asString() == RECORD }

    /** The components of [record]: the canonical constructor's parameters that have an accessor of their name. */
    fun components(record: KSClassDeclaration): List<Component> {
        if (!isRecord(record)) return emptyList()
        val functions = record.getDeclaredFunctions().toList()
        val accessors = functions.filter { it.simpleName.asString() != CONSTRUCTOR && it.parameters.isEmpty() }
            .associateBy { it.simpleName.asString() }
        // The canonical constructor: every parameter a component, of the most parameters; another constructor of a
        // record has a parameter of no component, or fewer.
        val canonical = functions.filter { it.simpleName.asString() == CONSTRUCTOR }
            .filter { constructor -> constructor.parameters.all { it.name?.asString() in accessors } }
            .maxByOrNull { it.parameters.size }
            ?: return emptyList()
        return canonical.parameters.mapNotNull { parameter ->
            val name = parameter.name?.asString() ?: return@mapNotNull null
            val accessor = accessors[name] ?: return@mapNotNull null
            Component(name, accessor.returnType?.resolve(), listOf(parameter, accessor))
        }
    }

    /** The component of [record] named [name] whose type is [type], by qualified name; null when it has none. */
    fun component(record: KSClassDeclaration, name: String, type: Set<String>): Component? =
        components(record).firstOrNull { it.name == name && it.type?.declaration?.qualifiedName?.asString() in type }

    /** Whether [function] of [record] is a site of its components: the canonical constructor, or an accessor. */
    fun isComponentSite(record: KSClassDeclaration, function: KSFunctionDeclaration): Boolean {
        val names = components(record).map { it.name }
        val name = function.simpleName.asString()
        return name == CONSTRUCTOR && function.parameters.any { it.name?.asString() in names } ||
            function.parameters.isEmpty() && name in names
    }

    /** The origins of a Java declaration, compiled here or in a library. */
    val JAVA: Set<Origin> = setOf(Origin.JAVA, Origin.JAVA_LIB)
}
