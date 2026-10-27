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

package me.ahoo.wow.spi.json

import me.ahoo.wow.api.annotation.InternalWowApi
import tools.jackson.databind.BeanProperty
import tools.jackson.databind.JavaType
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper
import tools.jackson.databind.jsonFormatVisitors.JsonObjectFormatVisitor
import tools.jackson.databind.jsonFormatVisitors.JsonStringFormatVisitor
import tools.jackson.databind.module.SimpleModule
import java.security.MessageDigest
import java.util.TreeMap
import java.util.TreeSet

/**
 * An aggregate's state as JSON, as Wow writes and reads it ([mapper], from [WowJson]'s configuration), and the
 * fingerprint of that JSON's shape ([fingerprint]): what a snapshot carries ([me.ahoo.wow.spi.snapshot.SnapshotRecord],
 * [me.ahoo.wow.spi.state.StateView]) and the Manifest records for a state, so a snapshot written under another shape
 * is replayed from the log instead of read back (design 03 §3). The kernel's event codec and the Manifest's generator
 * both take them from here, so the two always agree.
 */
@InternalWowApi
object StateJson {
    private val PLATFORM_PACKAGES = listOf("java.", "javax.", "kotlin.")

    /** The mapper of a state's JSON: [WowJson]'s, with the settings a mutable state's round trip needs. */
    val mapper: JsonMapper = WowJson.builder()
        // A mutable state holds collections in read-only properties; reading a snapshot fills them in place.
        .enable(MapperFeature.USE_GETTERS_AS_SETTERS)
        // Sets and maps read back in the order they were written, as Kotlin's keep theirs. Jackson would read a
        // `Set` as a HashSet (of enums, an EnumSet) and a `Map` keyed by an enum as an EnumMap, which write back in
        // hash or ordinal order: a state with such a set would not hold its own text, and a state read from a snapshot
        // would not write back as the snapshot holds it.
        .addModule(
            SimpleModule("wow-ordered-collections")
                .addAbstractTypeMapping(Set::class.java, LinkedHashSet::class.java)
                .addAbstractTypeMapping(Map::class.java, LinkedHashMap::class.java),
        )
        .build()

    /**
     * Identifies the shape of [stateType]'s JSON as [mapper] serializes it: the SHA-256 hash of a structural
     * description built from what Jackson's format visitor reports, that is from the serializers the mapper would use,
     * after naming strategies, ignored properties and the Kotlin module apply ([shape]):
     * - a bean is `{name:shape,...}` over its serialized properties, sorted by name, each property's declared type
     *   described in turn (type arguments resolved);
     * - an enum is `enum(A,B,...)` over its serialized constants, sorted;
     * - a primitive, a container or a `java.*`, `javax.*` or `kotlin.*` type is its class name with its type arguments
     *   described, `java.util.List<{...}>`; an array is its element's shape followed by `[]`;
     * - a type Jackson writes as a scalar (a custom serializer, `@JsonValue`) is its class name;
     * - a type met again inside itself is a back-reference `^n`, `n` levels up, so recursive states terminate.
     *
     * The description names no class of the state graph outside the platform packages and sorts every list, so it
     * does not depend on the JVM run, declaration order or map ordering, and moving or renaming a class keeps it: the
     * state's JSON does not carry class names. Adding, removing, renaming or retyping a serialized property, of the
     * state or of any type it holds, changes it, as does adding or removing an enum constant. Nullability,
     * required-ness and subtypes behind an abstract property type are not part of it: a snapshot that does not decode
     * any more is replayed from the log as well.
     */
    fun fingerprint(stateType: Class<*>, mapper: JsonMapper = this.mapper): String =
        hex(MessageDigest.getInstance("SHA-256").digest(shape(stateType, mapper).toByteArray(Charsets.UTF_8)))

    /** The description [fingerprint] hashes. */
    fun shape(stateType: Class<*>, mapper: JsonMapper = this.mapper): String =
        describe(mapper, mapper.constructType(stateType), emptyList())

    /** The JSON shape of [type], met within the types of [path]. */
    private fun describe(mapper: JsonMapper, type: JavaType, path: List<JavaType>): String {
        val cycle = path.lastIndexOf(type)
        if (cycle >= 0) {
            return "^${path.size - cycle}"
        }
        val within = path + type
        if (type.isArrayType) {
            return describe(mapper, type.contentType, within) + "[]"
        }
        if (type.isPrimitive || type.isContainerType || isPlatform(type.rawClass)) {
            val arguments = type.bindings.typeParameters
            return type.rawClass.name +
                if (arguments.isEmpty()) "" else arguments.joinToString(",", "<", ">") { describe(mapper, it, within) }
        }
        val visitor = ShapeVisitor()
        mapper.acceptJsonFormatVisitor(type, visitor)
        return when {
            visitor.isObject -> visitor.properties.entries.joinToString(",", "{", "}") { (name, propertyType) ->
                "$name:${describe(mapper, propertyType, within)}"
            }

            visitor.constants.isNotEmpty() -> visitor.constants.joinToString(",", "enum(", ")")
            else -> type.rawClass.name
        }
    }

    private fun isPlatform(rawClass: Class<*>): Boolean = PLATFORM_PACKAGES.any { rawClass.name.startsWith(it) }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Collects the serialized properties of a bean, or the serialized constants of an enum. */
    private class ShapeVisitor : JsonFormatVisitorWrapper.Base() {
        val properties = TreeMap<String, JavaType>()
        val constants = TreeSet<String>()
        var isObject = false

        override fun expectObjectFormat(type: JavaType): JsonObjectFormatVisitor {
            isObject = true
            return object : JsonObjectFormatVisitor.Base(context) {
                override fun property(prop: BeanProperty) {
                    properties[prop.name] = prop.type
                }

                override fun optionalProperty(prop: BeanProperty) {
                    properties[prop.name] = prop.type
                }
            }
        }

        override fun expectStringFormat(type: JavaType): JsonStringFormatVisitor =
            object : JsonStringFormatVisitor.Base() {
                override fun enumTypes(enums: Set<String>) {
                    constants += enums
                }
            }
    }
}
