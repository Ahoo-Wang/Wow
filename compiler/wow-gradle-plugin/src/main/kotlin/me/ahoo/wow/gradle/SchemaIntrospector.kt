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

package me.ahoo.wow.gradle

import com.fasterxml.classmate.ResolvedType
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomDefinitionProviderV2
import com.github.victools.jsonschema.generator.FieldScope
import com.github.victools.jsonschema.generator.MemberScope
import com.github.victools.jsonschema.generator.Option
import com.github.victools.jsonschema.generator.OptionPreset
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import com.github.victools.jsonschema.generator.SchemaGenerator
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import com.github.victools.jsonschema.generator.SchemaVersion
import com.github.victools.jsonschema.generator.impl.DefinitionKey
import com.github.victools.jsonschema.generator.naming.SchemaDefinitionNamingStrategy
import com.github.victools.jsonschema.module.jackson.JacksonOption
import com.github.victools.jsonschema.module.jackson.JacksonSchemaModule
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.manifest.Manifest
import tools.jackson.databind.JavaType
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper
import tools.jackson.databind.jsonFormatVisitors.JsonObjectFormatVisitor
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.KotlinModule
import java.lang.reflect.Modifier
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.kotlinProperty

/**
 * Completes a Manifest fragment (decision D73): fills `$defs` with the JSON Schema 2020-12 of every class the
 * fragment refers to, introspected from the compiled classes [classLoader] loads, and sets each aggregate's state
 * schema fingerprint.
 *
 * - Schemas come from victools' generator with its Jackson module over a Kotlin-aware Jackson mapper, so they follow
 *   what Jackson writes: `@JsonProperty` names (also on a Kotlin constructor parameter, as Jackson reads them),
 *   `@JsonNaming`, `@JsonIgnore`, `@JsonValue` and `@JsonProperty` enums. A Kotlin property is required when its
 *   constructor parameter is non-null and has no default, and accepts null when its type is nullable.
 * - Every type the fragment refers to, and every type referred to more than once, is a definition keyed by its JVM
 *   binary name (`a.b.Outer$Inner`; a parameterized one as `a.b.Page(a.b.Item)`), the key the fragment's references
 *   use; a type referred to once is inlined.
 * - A custom serializer is opaque to introspection: [schemaOverrides] gives the schema of such a class, by binary
 *   name, and a class Jackson does not write as an object fails the generation until it has one.
 * - The fingerprint is the kernel's [EventCodec.stateSchemaFingerprint] with its default mapper, the one a snapshot
 *   is written under (design 03 §3).
 */
internal class SchemaIntrospector(
    private val classLoader: ClassLoader,
    private val schemaOverrides: Map<String, String> = emptyMap(),
) {
    private val mapper: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val codec = EventCodec()

    fun complete(fragment: Manifest): Manifest {
        val keys = fragment.unresolvedSchemaRefs.map(Manifest::schemaKey)
        val builder = generator().buildMultipleSchemaDefinitions()
        val references = keys.associateWith { builder.createSchemaReference(load(it)) }
        val definitions = builder.collectDefinitions(DEFS)
        // A type referred to only by the fragment is inlined into its reference: it becomes the definition.
        val defined = definitions.properties().associate { it.key to it.value }
        val inlined = references.filterKeys { !definitions.has(it) }
        val generated = (defined + inlined).mapValues { (_, schema) -> mapper.convertValue(schema, Map::class.java) }
        return fragment.copy(
            contexts = fragment.contexts.map { context ->
                context.copy(
                    aggregates = context.aggregates.map { aggregate ->
                        val stateType = load(Manifest.schemaKey(aggregate.state.schema))
                        val fingerprint = fingerprint(AggregateType(context.name, aggregate.name), stateType)
                        aggregate.copy(state = aggregate.state.copy(fingerprint = fingerprint))
                    },
                )
            },
            defs = (fragment.defs + generated).toSortedMap(),
        )
    }

    private fun load(binaryName: String): Class<*> = Class.forName(binaryName, false, classLoader)

    /** The kernel's fingerprint of [stateType]: only the model's state type takes part in it. */
    @Suppress("UNCHECKED_CAST")
    private fun fingerprint(type: AggregateType, stateType: Class<*>): String =
        codec.stateSchemaFingerprint(
            AggregateModel(
                type = type,
                stateType = stateType as Class<Any>,
                initial = { error("Not built to run.") },
                deciders = emptyMap(),
                evolvers = emptyMap(),
                eventTypes = emptyList(),
                commandTypes = emptyList(),
            ),
        )

    private fun generator(): SchemaGenerator {
        val builder = SchemaGeneratorConfigBuilder(mapper, SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
            .with(
                JacksonNames(
                    JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
                    JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE,
                    JacksonOption.FLATTENED_ENUMS_FROM_JSONPROPERTY,
                ),
            )
            .with(Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES)
        builder.forTypesInGeneral()
            .withDefinitionNamingStrategy(BinaryNames)
            .withCustomDefinitionProvider(
                Overrides(schemaOverrides.mapValues { (type, schema) -> parse(type, schema) }, mapper),
            )
        builder.forFields()
            .withRequiredCheck(::isKotlinRequired)
            .withNullableCheck(::isKotlinNullable)
        return SchemaGenerator(builder.build())
    }

    private fun parse(type: String, schema: String): ObjectNode {
        val node = mapper.readTree(schema)
        require(node is ObjectNode) { "The schema override of $type is not a JSON object: $schema" }
        return node
    }

    /** The Jackson module, also taking a property's name from Jackson's own view of the bean. */
    private class JacksonNames(vararg options: JacksonOption) : JacksonSchemaModule(*options) {
        override fun getPropertyNameOverrideBasedOnJsonPropertyAnnotation(member: MemberScope<*, *>): String? {
            super.getPropertyNameOverrideBasedOnJsonPropertyAnnotation(member)?.let { return it }
            if (member !is FieldScope) {
                return null
            }
            // A Kotlin constructor parameter's @JsonProperty is seen by Jackson, not on the field victools reads.
            val property = getBeanDescriptionForClass(member.declaringType).findProperties()
                .firstOrNull { it.internalName == member.declaredName }
            return property?.name?.takeIf { it != member.declaredName }
        }
    }

    /** Definitions keyed by the JVM binary name of their type, so the fragment's references find them. */
    private object BinaryNames : SchemaDefinitionNamingStrategy {
        override fun getDefinitionNameForKey(key: DefinitionKey, context: SchemaGenerationContext): String =
            name(key.type)

        private fun name(type: ResolvedType): String =
            type.erasedType.name + type.typeParameters.let { parameters ->
                if (parameters.isEmpty()) "" else parameters.joinToString(",", "(", ")") { name(it) }
            }
    }

    /**
     * The declared schema of an overridden type. A concrete class of the application that Jackson does not write as
     * an object (a custom serializer, a `@JsonValue`) and that has no override fails the generation: its schema is
     * declared, not guessed.
     */
    private class Overrides(
        private val schemas: Map<String, ObjectNode>,
        private val mapper: JsonMapper,
    ) : CustomDefinitionProviderV2 {
        override fun provideCustomSchemaDefinition(
            type: ResolvedType,
            context: SchemaGenerationContext
        ): CustomDefinition? {
            val rawClass = type.erasedType
            schemas[rawClass.name]?.let { return CustomDefinition(it.deepCopy()) }
            check(!isApplicationClass(rawClass) || isWrittenAsObject(rawClass)) {
                "Jackson does not write ${rawClass.name} as an object (a custom serializer or @JsonValue), so its " +
                    "schema cannot be introspected: declare it in wow { schemaOverrides.put(\"${rawClass.name}\", " +
                    "\"<JSON Schema>\") }."
            }
            return null
        }

        private fun isApplicationClass(rawClass: Class<*>): Boolean =
            !rawClass.isPrimitive && !rawClass.isArray && !rawClass.isEnum && !rawClass.isInterface &&
                !Modifier.isAbstract(rawClass.modifiers) &&
                PLATFORM_PACKAGES.none { rawClass.name.startsWith(it) }

        private fun isWrittenAsObject(rawClass: Class<*>): Boolean {
            var isObject = false
            mapper.acceptJsonFormatVisitor(
                rawClass,
                object : JsonFormatVisitorWrapper.Base() {
                    override fun expectObjectFormat(type: JavaType): JsonObjectFormatVisitor? {
                        isObject = true
                        return null
                    }
                },
            )
            return isObject
        }
    }

    private companion object {
        const val DEFS = "\$defs"
        val PLATFORM_PACKAGES = listOf("java.", "javax.", "kotlin.")

        private fun kotlinProperty(field: FieldScope) =
            if (field.declaringType.erasedType.isAnnotationPresent(Metadata::class.java)) {
                runCatching { field.rawMember.kotlinProperty }.getOrNull()
            } else {
                null
            }

        /** A Kotlin property is required when its constructor parameter is non-null and has no default. */
        fun isKotlinRequired(field: FieldScope): Boolean {
            if (kotlinProperty(field) == null) {
                return false
            }
            val constructor = runCatching { field.declaringType.erasedType.kotlin.primaryConstructor }.getOrNull()
            val parameter = constructor?.parameters?.firstOrNull { it.name == field.declaredName } ?: return false
            return !parameter.isOptional && !parameter.type.isMarkedNullable
        }

        fun isKotlinNullable(field: FieldScope): Boolean = kotlinProperty(field)?.returnType?.isMarkedNullable == true
    }
}
