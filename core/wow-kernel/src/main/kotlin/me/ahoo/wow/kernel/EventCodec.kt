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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import tools.jackson.databind.BeanProperty
import tools.jackson.databind.JavaType
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper
import tools.jackson.databind.jsonFormatVisitors.JsonObjectFormatVisitor
import tools.jackson.databind.jsonFormatVisitors.JsonStringFormatVisitor
import tools.jackson.module.kotlin.KotlinModule
import java.security.MessageDigest
import java.util.TreeMap
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns events and commands into what the log stores, and back. Events are named by the model, never by class name,
 * so renaming or moving a class does not orphan its history. States go into and come out of snapshots the same way.
 */
class EventCodec(private val mapper: JsonMapper = DEFAULT_MAPPER) {
    private val stateFingerprints = ConcurrentHashMap<Class<*>, String>()

    /** [event] as a record of [model]. */
    fun encode(model: AggregateModel<*>, event: Any): EventRecord {
        val type = model.eventType(event.javaClass)
        return EventRecord(type.name, type.revision, mapper.writeValueAsString(event))
    }

    /**
     * The event [record] holds, read as [model]'s current schema of its type: a record of an older revision goes
     * through the type's upgrades one revision at a time (D22); a record named by a former name is still found.
     */
    fun decode(model: AggregateModel<*>, record: EventRecord): Any {
        val type = model.eventType(record.name)
        check(record.revision <= type.revision) {
            "${record.name} revision ${record.revision} is newer than revision ${type.revision} this model reads."
        }
        if (record.revision == type.revision) {
            return mapper.readValue(record.payload, type.type)
        }
        var payload = mapper.readTree(record.payload)
        for (revision in record.revision until type.revision) {
            payload = type.upgrades.getValue(revision)(payload)
        }
        return mapper.treeToValue(payload, type.type)
    }

    /** [state] as text, to tell whether something changed it, and as a snapshot holds it. */
    fun stateText(state: Any): String = mapper.writeValueAsString(state)

    /**
     * The state [text] of a snapshot, read as [model]'s state class: a Jackson exception when it does not parse, an
     * [IllegalArgumentException] when the state read does not write back as the same JSON. A state that silently
     * dropped what it could not take back (a read-only property Jackson does not fill) would decide on lost data;
     * a computed property writes back the same and passes. Objects compare by field, so a backend that reorders
     * keys (PostgreSQL JSONB) does not fail it.
     */
    fun <S : Any> decodeState(model: AggregateModel<S>, text: String): S {
        val state = mapper.readValue(text, model.stateType)
        require(mapper.readTree(stateText(state)) == mapper.readTree(text)) {
            "A ${model.stateType.name} read from this text does not hold all of it."
        }
        return state
    }

    /**
     * The snapshot of [state], which folds [ref]'s event streams up to [version]; [firstEventTime] and
     * [lastEventTime] are the commit times of its first and last streams. Access facts and tags stay empty.
     */
    @Suppress("LongParameterList")
    fun <S : Any> snapshotOf(
        model: AggregateModel<S>,
        ref: AggregateRef,
        state: S,
        version: Int,
        firstEventTime: Long,
        lastEventTime: Long,
    ): SnapshotRecord {
        require(ref.type == model.type) { "$ref is not a ${model.type}." }
        return SnapshotRecord(
            ref = ref,
            version = version,
            schemaFingerprint = stateSchemaFingerprint(model),
            state = stateText(state),
            firstEventTime = firstEventTime,
            lastEventTime = lastEventTime,
        )
    }

    /**
     * Identifies the schema of [model]'s state as this codec's mapper serializes it: a snapshot written under another
     * fingerprint is not read back; the state is replayed from the log instead (03-log-and-snapshot.md §3).
     *
     * It is the SHA-256 hash of a structural description of the state's JSON shape, built from what Jackson's format
     * visitor reports, that is from the serializers this mapper would use, after naming strategies, ignored
     * properties and the Kotlin module apply:
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
     * any more is replayed from the log as well. Computed once per state class.
     */
    fun stateSchemaFingerprint(model: AggregateModel<*>): String =
        stateFingerprints.computeIfAbsent(model.stateType) { stateType ->
            hex(MessageDigest.getInstance("SHA-256").digest(stateShape(stateType).toByteArray(Charsets.UTF_8)))
        }

    /** The description [stateSchemaFingerprint] hashes. */
    internal fun stateShape(stateType: Class<*>): String = describe(mapper.constructType(stateType), emptyList())

    /** The JSON shape of [type], met within the types of [path]. */
    private fun describe(type: JavaType, path: List<JavaType>): String {
        val cycle = path.lastIndexOf(type)
        if (cycle >= 0) {
            return "^${path.size - cycle}"
        }
        val within = path + type
        if (type.isArrayType) {
            return describe(type.contentType, within) + "[]"
        }
        if (type.isPrimitive || type.isContainerType || isPlatform(type.rawClass)) {
            val arguments = type.bindings.typeParameters
            return type.rawClass.name +
                if (arguments.isEmpty()) "" else arguments.joinToString(",", "<", ">") { describe(it, within) }
        }
        val visitor = ShapeVisitor()
        mapper.acceptJsonFormatVisitor(type, visitor)
        return when {
            visitor.isObject -> visitor.properties.entries.joinToString(",", "{", "}") { (name, propertyType) ->
                "$name:${describe(propertyType, within)}"
            }

            visitor.constants.isNotEmpty() -> visitor.constants.joinToString(",", "enum(", ")")
            else -> type.rawClass.name
        }
    }

    private fun isPlatform(rawClass: Class<*>): Boolean = PLATFORM_PACKAGES.any { rawClass.name.startsWith(it) }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /**
     * Identifies a command: its type and a hash of its body. A request id reused by a command with another
     * fingerprint is a different command, not a retry (decision D46).
     */
    fun fingerprint(body: Any): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(body))
        return body.javaClass.name + ":" + hex(digest.copyOf(FINGERPRINT_BYTES))
    }

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

    private companion object {
        const val FINGERPRINT_BYTES = 16
        val PLATFORM_PACKAGES = listOf("java.", "javax.", "kotlin.")
        val DEFAULT_MAPPER: JsonMapper = JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            // A mutable state (D7) holds collections in read-only properties; reading a snapshot fills them in place.
            .enable(MapperFeature.USE_GETTERS_AS_SETTERS)
            .build()
    }
}
