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

package me.ahoo.wow.manifest.tools

import com.github.victools.jsonschema.generator.FieldScope
import com.github.victools.jsonschema.generator.MemberScope
import com.github.victools.jsonschema.generator.MethodScope
import me.ahoo.wow.api.query.annotation.QueryIndex
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Modifier

/**
 * A member of a supertype that a property overrides: its getter, and the field of the property when the supertype is a
 * class that declares one. The query annotations on them are read for the property ([QueryFieldKeywords]), as a
 * Kotlin property in a read model interface carries them for the states and the types that implement it.
 */
internal class OverriddenMember(
    private val owner: Class<*>,
    private val name: String,
    elements: List<AnnotatedElement>
) {
    /** Every annotation on the member's getter and field. */
    val annotations: List<Annotation> = elements.flatMap { it.annotations.asList() }

    /** The member's `@QueryIndex` declarations, a repeated one unwrapped from its container. */
    val indexes: List<QueryIndex> = elements.flatMap { it.getAnnotationsByType(QueryIndex::class.java).asList() }

    /** The member as a failure names it: `<supertype>.<property>`. */
    override fun toString(): String = "${owner.name}.$name"
}

/** The members a property overrides, in the supertypes of the class that declares it: interfaces and superclasses, transitively. */
internal object OverriddenMembers {
    /**
     * The members of [scope]'s property that its getter overrides, nearest supertype first: a supertype declares one
     * when it has a non-private, non-static getter of the same name, which the field of that name in a class joins.
     */
    fun of(scope: MemberScope<*, *>): List<OverriddenMember> {
        val getter = (if (scope is FieldScope) scope.findGetter()?.rawMember else (scope as MethodScope).rawMember)
            ?: return emptyList()
        val field = (scope as? FieldScope)?.declaredName
        return supertypes(scope.declaringType.erasedType).mapNotNull { type ->
            val overridden = type.declaredMethods.firstOrNull {
                it.name == getter.name && it.parameterCount == 0 &&
                    !Modifier.isStatic(it.modifiers) && !Modifier.isPrivate(it.modifiers)
            } ?: return@mapNotNull null
            val backing = field?.let { name -> runCatching { type.getDeclaredField(name) }.getOrNull() }
            OverriddenMember(type, field ?: getter.name, listOfNotNull(backing, overridden))
        }
    }

    /** The superclasses and interfaces of [type], the nearest first, each once; [Any] is none. */
    private fun supertypes(type: Class<*>): List<Class<*>> {
        val found = linkedSetOf<Class<*>>()
        val queue = ArrayDeque(listOf(type))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            listOfNotNull(current.superclass, *current.interfaces)
                .filter { it != Any::class.java && found.add(it) }
                .forEach(queue::addLast)
        }
        return found.toList()
    }
}
