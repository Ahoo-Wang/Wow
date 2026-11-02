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

import com.fasterxml.jackson.annotation.JsonSubTypes
import me.ahoo.wow.spi.json.StateJson
import tools.jackson.databind.BeanProperty
import tools.jackson.databind.JavaType
import tools.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper
import tools.jackson.databind.jsonFormatVisitors.JsonObjectFormatVisitor

/**
 * The types of Wow's query protocol (the classes of `me.ahoo.wow.api.query`: a filter, a sort, a projection, a field)
 * that an event or a state holds, anywhere in what Wow's state mapper writes of it ([StateJson.mapper]): its properties, the elements,
 * keys and values of its containers, the type arguments of a platform type, and the subtypes of a sealed or
 * `@JsonSubTypes` type. They are wire types, which evolve with Wow, and Wow's JSON writes them but does not read them
 * back from a store, so an event or a state holds a type of its own instead. A command or a query holds them freely.
 * The package's `annotation` subpackage is not the protocol: a domain may hold the level of a field's sensitivity
 * ([me.ahoo.wow.api.query.annotation.SensitivityLevel]), a plain enumeration.
 */
internal object StoredQueryTypes {
    /** One place an event or a state holds a query type: the [path] to it from the class, and the [type]. */
    data class Held(val path: String, val type: Class<*>) {
        override fun toString(): String = "$path (${type.name})"
    }

    /** Where [root], an event's or a state's class, holds a query type; empty when it holds none. */
    fun of(root: Class<*>): List<Held> = Walk().also { it.visit(StateJson.mapper.constructType(root), root.name) }.held

    /** The failure of [held], the query types the events and states hold, naming each and what to do instead. */
    fun problem(held: List<Held>): String =
        "Events and states hold types of Wow's query protocol: ${held.joinToString("; ")}. They are wire types that " +
            "evolve with Wow, which its JSON writes and does not read back from a store: hold a type of your own " +
            "instead, and upgrade a 9.x event or state that held one into it with an @Upgrade function " +
            "($MIGRATE_GUIDE)."

    private const val QUERY_PACKAGE = "me.ahoo.wow.api.query"
    private const val MIGRATE_GUIDE = "https://wow.ahoo.me/migrate/source#events-and-state"
    private val UNWALKED_PACKAGES = listOf("java.", "javax.", "kotlin.", "tools.jackson.")

    private class Walk {
        val held = mutableListOf<Held>()
        private val seen = HashSet<JavaType>()

        fun visit(type: JavaType, path: String) {
            val rawClass = type.rawClass
            when {
                rawClass.packageName == QUERY_PACKAGE -> held += Held(path, rawClass)
                type.isArrayType || type.isCollectionLikeType -> visit(type.contentType, "$path[]")
                type.isMapLikeType -> {
                    visit(type.keyType, "$path{key}")
                    visit(type.contentType, "$path{}")
                }

                type.isPrimitive || rawClass.isEnum || !seen.add(type) -> Unit
                UNWALKED_PACKAGES.any { rawClass.name.startsWith(it) } ->
                    type.bindings.typeParameters.forEach { visit(it, path) }

                else -> {
                    for (subtype in subtypesOf(rawClass)) {
                        visit(StateJson.mapper.constructType(subtype), "$path<${subtype.simpleName}>")
                    }
                    propertiesOf(type).forEach { (name, propertyType) -> visit(propertyType, "$path.$name") }
                }
            }
        }

        /** The subtypes [type] is written as: a Kotlin sealed type's, and those its `@JsonSubTypes` lists. */
        private fun subtypesOf(type: Class<*>): List<Class<*>> {
            val sealed = if (type.isAnnotationPresent(Metadata::class.java)) {
                type.kotlin.sealedSubclasses.map { it.java }
            } else {
                emptyList()
            }
            val listed = type.getAnnotation(JsonSubTypes::class.java)?.value?.map { it.value.java }.orEmpty()
            return (sealed + listed).distinct()
        }

        /** The properties Wow's state mapper writes of [type], with their types; none when it is no object. */
        private fun propertiesOf(type: JavaType): Map<String, JavaType> {
            val properties = LinkedHashMap<String, JavaType>()
            StateJson.mapper.acceptJsonFormatVisitor(
                type,
                object : JsonFormatVisitorWrapper.Base() {
                    override fun expectObjectFormat(type: JavaType): JsonObjectFormatVisitor =
                        object : JsonObjectFormatVisitor.Base(context) {
                            override fun property(prop: BeanProperty) {
                                properties[prop.name] = prop.type
                            }

                            override fun optionalProperty(prop: BeanProperty) {
                                properties[prop.name] = prop.type
                            }
                        }
                },
            )
            return properties
        }
    }
}
