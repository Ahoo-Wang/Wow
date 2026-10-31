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

import com.fasterxml.classmate.ResolvedType
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomDefinitionProviderV2
import com.github.victools.jsonschema.generator.CustomPropertyDefinition
import com.github.victools.jsonschema.generator.CustomPropertyDefinitionProvider
import com.github.victools.jsonschema.generator.FieldScope
import com.github.victools.jsonschema.generator.MemberScope
import com.github.victools.jsonschema.generator.MethodScope
import com.github.victools.jsonschema.generator.Option
import com.github.victools.jsonschema.generator.OptionPreset
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import com.github.victools.jsonschema.generator.SchemaGenerator
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import com.github.victools.jsonschema.generator.SchemaVersion
import com.github.victools.jsonschema.generator.TypeAttributeOverrideV2
import com.github.victools.jsonschema.generator.TypeScope
import com.github.victools.jsonschema.generator.impl.DefinitionKey
import com.github.victools.jsonschema.generator.naming.SchemaDefinitionNamingStrategy
import com.github.victools.jsonschema.module.jackson.JacksonOption
import com.github.victools.jsonschema.module.jackson.JacksonSchemaModule
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.api.wire.WireNames
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.spi.json.StateJson
import me.ahoo.wow.spi.json.WowEnums
import me.ahoo.wow.spi.snapshot.RebuildSnapshot
import me.ahoo.wow.spi.snapshot.SnapshotRebuilds
import tools.jackson.databind.BeanProperty
import tools.jackson.databind.JavaType
import tools.jackson.databind.introspect.BeanPropertyDefinition
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper
import tools.jackson.databind.jsonFormatVisitors.JsonObjectFormatVisitor
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.KotlinModule
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KType
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.kotlinProperty

/**
 * Completes a Manifest fragment: fills `$defs` with the JSON Schema 2020-12 of every class the
 * fragment refers to, introspected from the compiled classes [classLoader] loads, and sets each aggregate's state
 * schema fingerprint.
 *
 * - Schemas come from victools' generator with its Jackson module over a Kotlin-aware Jackson mapper, so they follow
 *   what Jackson writes: `@JsonProperty` names (also on a Kotlin constructor parameter, as Jackson reads them),
 *   `@JsonNaming`, `@JsonIgnore`, `@JsonValue` and `@JsonProperty` enums. A Kotlin property accepts null when its
 *   type is nullable, and is required when it is not and no reader can leave it out: a constructor parameter without
 *   a default, or a property of the class body (a state's `var name: String = ""`), which Jackson always writes. A
 *   nullable property, and a constructor parameter with a default, are optional.
 * - A computed property, a getter Jackson writes with no field behind it (`val total: Int get() = ...`), is a property
 *   of the schema too, named as Jackson writes it and `readOnly`: a snapshot holds it, so a query may name it, and
 *   nothing reads it back. It is required unless its Kotlin type is nullable, and carries the query keywords of its
 *   annotations and value type as a field does ([JacksonNames.computed]).
 * - A polymorphic type (`@JsonSubTypes`) is a definition of its subtypes' alternatives (`anyOf`), each with its
 *   discriminator, and the base class's `x-wow-class` ([Polymorphic]).
 * - A type is described (`description`) by its `@Description`, else its `@Summary`; a command or an event by the
 *   description its fragment entry carries, which the compiler also reads from the KDoc (.1c).
 * - The body of each system command the fragment lists (`wow.lifecycle.TransferOwner`, `wow.snapshot.Rebuild`,
 *   `wow.schedule.Enable`) is a definition keyed by that full name, introspected from its class (`SYSTEM_BODIES`) like
 *   a command's.
 * - Every type the fragment refers to, and every type referred to more than once, is a definition keyed by its JVM
 *   binary name (`a.b.Outer$Inner`; a parameterized one as `a.b.Page(a.b.Item)`), the key the fragment's references
 *   use; a type referred to once is inlined. A property's list, set, object array or map is no named type: it is
 *   written where it is used, each element or value nullable as its Kotlin type argument is ([Containers]).
 * - A custom serializer is opaque to introspection: [schemaOverrides] gives the schema of such a class, by binary
 *   name, and a class Jackson does not write as an object fails the generation until it has one.
 * - A member's Jakarta Bean Validation constraints become the validation keywords of its schema, as victools' Jakarta
 *   module maps them: `@NotBlank` is `minLength: 1`, `@Positive` is `exclusiveMinimum: 0`, `@Size` is
 *   `minLength`/`maxLength` (`minItems`/`maxItems` on a collection), `@Min`/`@Max` and `@DecimalMin`/`@DecimalMax` the
 *   bounds, `@Pattern` the `pattern`, `@Email` the `email` format. They do not decide requiredness or nullability,
 *   which stay the Kotlin type's: a `@NotNull` on a nullable Kotlin type does not make it required.
 * - The query field annotations of `wow-query-api` become `x-wow-*` keywords of the property schemas
 *   ([QueryKeywords]), and the `@QueryIndex` declarations of a state the `x-wow-index` of its schema
 *   ([QueryIndexes]); an invalid declaration fails the generation.
 * - The fingerprint is the shape of the state's JSON as Wow writes it ([StateJson.fingerprint]), the one a snapshot
 *   is written under.
 */
internal class SchemaIntrospector(
    private val classLoader: ClassLoader,
    private val schemaOverrides: Map<String, String> = emptyMap(),
) {
    private val mapper: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    fun complete(fragment: Manifest): Manifest {
        val aggregates = fragment.contexts.flatMap { it.aggregates }
        val types = fragment.unresolvedSchemaRefs.map(Manifest::schemaKey)
            .filterNot { it.startsWith(CommandRoutes.SYSTEM_SEGMENT_PREFIX) }.associateWith(::load) +
            systemBodies(fragment)
        val states = aggregates.map { load(Manifest.schemaKey(it.state.schema)) }
        val queryKeywords = QueryFieldKeywords(states.toSet())
        val builder = generator(queryKeywords).buildMultipleSchemaDefinitions()
        val references = types.mapValues { (_, type) -> builder.createSchemaReference(type) }
        val definitions = builder.collectDefinitions(DEFS)
        // A type referred to only by the fragment is inlined into its reference: it becomes the definition.
        val defined = definitions.properties().associate { it.key to it.value }
        val inlined = references.filterKeys { !definitions.has(it) }
        val generated = (defined + inlined).mapValues { (_, schema) -> schemaMap(schema) }
        val defs = fragment.defs + described(generated, aggregates)
        return fragment.copyWith(
            contexts =
            fragment.contexts.map { context ->
                context.copyWith(
                    aggregates =
                    context.aggregates.map { aggregate ->
                        val stateType = load(Manifest.schemaKey(aggregate.state.schema))
                        val fingerprint = fingerprint(stateType)
                        aggregate.copyWith(state = aggregate.state.copyWith(fingerprint = fingerprint))
                    },
                )
            },
        ).copyWith(defs = (defs + queryIndexes(fragment, queryKeywords, defs)).toSortedMap())
    }

    /** The classes of the system commands [fragment] lists, by their full names, that its defs do not hold. */
    private fun systemBodies(fragment: Manifest): Map<String, Class<*>> =
        fragment.contexts.flatMap { context -> context.commands + context.aggregates.flatMap { it.commands } }
            .filter { it.system }.map { Manifest.schemaKey(it.schema) }.distinct().filter { it !in fragment.defs }
            .mapNotNull { name -> SYSTEM_BODIES[name]?.let { name to it } }.toMap()

    /**
     * The [generated] schemas, each command's and event's described as its entry of [aggregates] says: the compiler
     * read that description from the class's KDoc too, which introspection cannot see.
     */
    private fun described(
        generated: Map<String, Map<String, Any?>>,
        aggregates: List<AggregateManifest>,
    ): Map<String, Any?> {
        val descriptions = aggregates.flatMap { aggregate ->
            aggregate.commands.map { it.schema to it.description } + aggregate.events.map { it.schema to it.description }
        }.mapNotNull { (ref, description) -> description?.let { Manifest.schemaKey(ref) to it } }.toMap()
        return generated.mapValues { (key, schema) ->
            descriptions[key]?.let { schema + (DESCRIPTION to it) } ?: schema
        }
    }

    /**
     * The state schemas of [fragment] that declare query indexes, with their `x-wow-index` ([QueryIndexes]); fails
     * on an invalid index, on a name repeated within a context, and on such a state shared by several aggregates.
     */
    private fun queryIndexes(
        fragment: Manifest,
        queryKeywords: QueryFieldKeywords,
        defs: Map<String, Any?>,
    ): Map<String, Any?> {
        val indexed = mutableMapOf<String, String>()
        return fragment.contexts.flatMap { context ->
            val names = mutableMapOf<String, String>()
            context.aggregates.mapNotNull { aggregate ->
                val key = Manifest.schemaKey(aggregate.state.schema)
                val state = load(key)
                val indexes = QueryIndexes.of(aggregate.name, state, queryKeywords.propertyIndexes(state), defs)
                if (indexes.isEmpty()) return@mapNotNull null
                val owner = "${context.name}.${aggregate.name}"
                indexed.put(key, owner)?.let { other ->
                    error(
                        "The state ${state.name} declares query indexes, so it is the state of one aggregate " +
                            "only, but $other and $owner share it."
                    )
                }
                indexes.forEach { index ->
                    val name = index.getValue("name") as String
                    names.put(name, owner)?.let { other ->
                        error(
                            "The query index name $name is declared twice in the context ${context.name}: by " +
                                "$other and $owner."
                        )
                    }
                }
                @Suppress("UNCHECKED_CAST")
                val schema = defs[key] as Map<String, Any?>
                key to schema + (QueryKeywords.INDEX_KEYWORD to indexes)
            }
        }.toMap()
    }

    @Suppress("UNCHECKED_CAST")
    private fun schemaMap(schema: Any): Map<String, Any?> =
        mapper.convertValue(schema, Map::class.java) as Map<String, Any?>

    private fun load(binaryName: String): Class<*> = Class.forName(binaryName, false, classLoader)

    /** The fingerprint of [stateType]'s JSON, as a snapshot of it carries. */
    private fun fingerprint(stateType: Class<*>): String = StateJson.fingerprint(stateType)

    private fun generator(queryKeywords: QueryFieldKeywords): SchemaGenerator {
        val builder = SchemaGeneratorConfigBuilder(mapper, SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
        // A polymorphic property is its type's definition ([Polymorphic]), not alternatives the generator writes per
        // property, which name no base class: ahead of the Jackson module's own resolution.
        builder.forFields().withTargetTypeOverridesResolver { Polymorphic.declared(it) }
        builder.forMethods().withTargetTypeOverridesResolver { Polymorphic.declared(it) }
        builder
            .with(
                JacksonNames(
                    JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
                    JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE,
                    JacksonOption.FLATTENED_ENUMS_FROM_JSONPROPERTY,
                ),
            )
            // The argument-free methods are the candidates of the computed properties ([JacksonNames.computed]),
            // which keeps those Jackson writes, each named as a property, `total` of `getTotal()`, not as a method.
            .with(
                Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES,
                Option.NONSTATIC_NONVOID_NONGETTER_METHODS,
                Option.FIELDS_DERIVED_FROM_ARGUMENTFREE_METHODS,
            )
        builder.forTypesInGeneral()
            .withDefinitionNamingStrategy(BinaryNames)
            .withCustomDefinitionProvider(
                Overrides(schemaOverrides.mapValues { (type, schema) -> parse(type, schema) }, mapper),
            )
            .withCustomDefinitionProvider(Polymorphic)
            .withTypeAttributeOverride(queryKeywords)
            .withTypeAttributeOverride(WireFormats)
            .withDescriptionResolver { scope -> describe(scope.type.erasedType) }
        builder.forFields()
            .withRequiredCheck(::isKotlinRequired)
            .withNullableCheck { isKotlinNullable(it) }
            .withCustomDefinitionProvider(Containers.of<FieldScope>())
            .withInstanceAttributeOverride { attributes, field, _ ->
                queryKeywords.overrideMemberAttributes(
                    attributes,
                    field
                )
            }
        // A computed property is read-only: Jackson writes it, and nothing reads it back. It is always written, so it is
        // required unless its Kotlin type is nullable.
        builder.forMethods()
            .withReadOnlyCheck { true }
            .withRequiredCheck { KotlinMembers.typeOf(it)?.isMarkedNullable == false }
            .withNullableCheck { isKotlinNullable(it) }
            .withCustomDefinitionProvider(Containers.of<MethodScope>())
            .withInstanceAttributeOverride { attributes, method, _ ->
                queryKeywords.overrideMemberAttributes(
                    attributes,
                    method
                )
            }
        // The Jakarta constraints as validation keywords (`@NotBlank` is `minLength: 1`, `@Positive` `exclusiveMinimum:
        // 0`, `@Size`, `@Min`, `@Max`, `@Pattern`, `@Email`, ...), applied after the checks above: a member's
        // requiredness and nullability stay its Kotlin type's, which the module's own checks, asked after them, do
        // not override.
        builder.with(JakartaValidationModule(JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS))
        return SchemaGenerator(builder.build())
    }

    /**
     * A property's list, set, object array or map, written where it is used, never as a definition of its erased type
     * (two `Map<String, Long>` and one `Map<String, Long?>` are three schemas, not one shared definition): each element
     * or value accepts null when its Kotlin type argument is nullable, at any depth (`List<Map<String, Item?>>`), the
     * way a nullable property does, a scalar by a type union and any other value by a `null` alternative beside its
     * schema. A Java or platform type argument is not nullable; a primitive array is left to the generator.
     */
    private object Containers {
        /** The provider of the properties [M] are: fields, or the getters of computed properties. */
        fun <M : MemberScope<*, *>> of(): CustomPropertyDefinitionProvider<M> =
            CustomPropertyDefinitionProvider { member, context ->
                if (member.isFakeContainerItemScope) return@CustomPropertyDefinitionProvider null
                container(member.type, KotlinMembers.typeOf(member), context)
                    ?.let { CustomPropertyDefinition(it, CustomDefinition.AttributeInclusion.YES) }
            }

        /** The schema of [type] when it is a container, its elements typed by [kotlinType]'s arguments, else null. */
        private fun container(type: ResolvedType, kotlinType: KType?, context: SchemaGenerationContext): ObjectNode? {
            val erased = type.erasedType
            val (keyword, element, argument) = when {
                erased.isArray && !erased.componentType.isPrimitive ->
                    Triple(ITEMS, type.arrayElementType, 0)
                Collection::class.java.isAssignableFrom(erased) ->
                    Triple(ITEMS, context.typeContext.getContainerItemType(type), 0)
                Map::class.java.isAssignableFrom(erased) ->
                    Triple(ADDITIONAL_PROPERTIES, type.typeParametersFor(Map::class.java)?.getOrNull(1), 1)
                else -> return null
            }
            val elementKotlinType = kotlinType?.arguments?.getOrNull(argument)?.type
            return context.generatorConfig.createObjectNode().apply {
                put(TYPE, if (keyword == ITEMS) ARRAY else OBJECT)
                // Any value at all, null included, is left unsaid, as the generator leaves it out of a free map.
                element?.takeIf { it.erasedType != Any::class.java }?.let {
                    set(keyword, element(it, elementKotlinType, context))
                }
                if (Set::class.java.isAssignableFrom(erased)) put(WireTypes.UNIQUE_ITEMS, true)
            }
        }

        private fun element(type: ResolvedType, kotlinType: KType?, context: SchemaGenerationContext): ObjectNode {
            val nullable = kotlinType?.isMarkedNullable == true
            container(type, kotlinType, context)?.let { return if (nullable) context.makeNullable(it) else it }
            if (!nullable) return context.createDefinitionReference(type)
            if (isScalar(type.erasedType)) return context.makeNullable(context.createDefinition(type))
            // The reference is filled in, or replaced by a definition's reference, once the generation ends.
            return context.generatorConfig.createObjectNode().apply {
                putArray(ANY_OF)
                    .add(context.generatorConfig.createObjectNode().put(TYPE, NULL))
                    .add(context.createDefinitionReference(type))
            }
        }

        /** A value of the platform written as a JSON scalar: a number, a string, a boolean, an enumeration, a time. */
        private fun isScalar(type: Class<*>): Boolean =
            type.isPrimitive || type.isEnum || PLATFORM_PACKAGES.any { type.name.startsWith(it) }

        const val TYPE = "type"
        const val ITEMS = "items"
        const val ADDITIONAL_PROPERTIES = "additionalProperties"
        const val ANY_OF = "anyOf"
        const val ARRAY = "array"
        const val OBJECT = "object"
        const val NULL = "null"
    }

    private fun parse(type: String, schema: String): ObjectNode {
        val node = mapper.readTree(schema)
        require(node is ObjectNode) { "The schema override of $type is not a JSON object: $schema" }
        return node
    }

    /**
     * The Jackson module, also taking a property's name from Jackson's own view of the bean, and keeping, of the
     * methods, the getters of the computed properties ([computed]).
     */
    private class JacksonNames(vararg options: JacksonOption) : JacksonSchemaModule(*options) {
        override fun applyToConfigBuilder(builder: SchemaGeneratorConfigBuilder) {
            super.applyToConfigBuilder(builder)
            builder.forMethods().withIgnoreCheck { computed(it) == null }
        }

        /** The names of the properties Wow's JSON writes of each class ([StateJson.mapper]), by class. */
        private val written = ConcurrentHashMap<Class<*>, Set<String>>()

        /**
         * The property Jackson writes by [method], a getter with no field behind it: a computed property, such as a
         * Kotlin `val total: Int get() = ...`, which a snapshot of the state holds. Null for any other method: a
         * function, a getter Jackson ignores (`@get:JsonIgnore`), the getter of a field, and a getter Wow's JSON
         * does not write, of a class it writes with a serializer of its own (an `AggregateRef`'s `isAssigned`).
         */
        fun computed(method: MethodScope): BeanPropertyDefinition? {
            // A getter takes no argument, so a function of arguments is no property's; nor is a static method, which
            // the generator does not offer. A computed container's items are no member of their own: [Containers]
            // writes the container whole, its items referring to their type, so no item scope is offered.
            val property = getBeanDescriptionForClass(method.declaringType).findProperties()
                .firstOrNull { it.getter?.annotated == method.rawMember } ?: return null
            val declaring = method.declaringType.erasedType
            val backed = generateSequence(declaring) { it.superclass }.any { type ->
                type.declaredFields.any { it.name == property.internalName && !Modifier.isStatic(it.modifiers) }
            }
            val writes = property.name in written.computeIfAbsent(declaring, ::writtenNames)
            return property.takeIf { !backed && writes }
        }

        /** The names of the properties Wow's JSON writes of [type]: none when it does not write it as an object. */
        private fun writtenNames(type: Class<*>): Set<String> {
            val names = linkedSetOf<String>()
            StateJson.mapper.acceptJsonFormatVisitor(
                type,
                object : JsonFormatVisitorWrapper.Base() {
                    override fun expectObjectFormat(type: JavaType): JsonObjectFormatVisitor =
                        object : JsonObjectFormatVisitor.Base(context) {
                            override fun property(prop: BeanProperty) {
                                names += prop.name
                            }

                            override fun optionalProperty(prop: BeanProperty) {
                                names += prop.name
                            }
                        }
                },
            )
            return names
        }

        override fun getPropertyNameOverrideBasedOnJsonPropertyAnnotation(member: MemberScope<*, *>): String? {
            super.getPropertyNameOverrideBasedOnJsonPropertyAnnotation(member)?.let { return it }
            // A computed property is named as Jackson writes it: `total` of `getTotal()`.
            if (member is MethodScope) {
                return computed(member)?.name
            }
            if (member !is FieldScope) {
                return null
            }
            // A Kotlin constructor parameter's @JsonProperty is seen by Jackson, not on the field victools reads.
            val property = getBeanDescriptionForClass(member.declaringType).findProperties()
                .firstOrNull { it.internalName == member.declaredName }
            return property?.name?.takeIf { it != member.declaredName }
        }
    }

    /**
     * What a plain JSON type does not say of a value ([WireTypes]): its `format` (`int32`, `int64`, `decimal`,
     * `date-time`, ...), `uniqueItems` for a set, and `x-wow-class`, the binary name of a class the type and the format
     * do not name (an application's class, an enumeration, a platform value class such as an `OffsetDateTime`). The wire
     * keeps numbers and ISO-8601 strings; a client reads `int64`, `bigint` and `decimal` from their text.
     */
    private object WireFormats : TypeAttributeOverrideV2 {
        override fun overrideTypeAttributes(
            attributes: ObjectNode,
            scope: TypeScope,
            context: SchemaGenerationContext
        ) {
            val type = scope.type.erasedType
            WireTypes.FORMATS[type]?.let { format ->
                if (!attributes.has(WireTypes.FORMAT)) {
                    attributes.put(WireTypes.FORMAT, format)
                }
            }
            if (Set::class.java.isAssignableFrom(type)) {
                attributes.put(WireTypes.UNIQUE_ITEMS, true)
            }
            if (WireTypes.isNamed(type)) {
                attributes.put(WireTypes.CLASS_KEYWORD, classOf(scope.type))
            }
        }

        fun classOf(type: ResolvedType): String =
            WireTypes.binaryName(type.erasedType) + type.typeParameters.let { parameters ->
                if (parameters.isEmpty()) "" else parameters.joinToString(",", "(", ")") { BinaryNames.name(it) }
            }
    }

    /**
     * A polymorphic type (`@JsonTypeInfo` with `@JsonSubTypes`) as the generator writes it, the alternatives of its
     * subtypes (`anyOf`), each with its discriminator, and the `x-wow-class` of the base class, which the generator
     * leaves out of a polymorphic node: a reader of the schema names the type that holds them all, a state view the
     * base class on the api's class path.
     */
    private object Polymorphic : CustomDefinitionProviderV2 {
        /** Whether [type] declares its subtypes (`@JsonSubTypes`), which Jackson writes it as. */
        fun isPolymorphic(type: Class<*>): Boolean = type.isAnnotationPresent(JsonSubTypes::class.java)

        /** The type of [member] as its own target when it is polymorphic, so its definition is [Polymorphic]'s. */
        fun declared(member: MemberScope<*, *>): List<ResolvedType>? =
            listOf(member.type).takeIf { isPolymorphic(member.type.erasedType) }

        override fun provideCustomSchemaDefinition(type: ResolvedType, context: SchemaGenerationContext): CustomDefinition? {
            if (!isPolymorphic(type.erasedType)) {
                return null
            }
            val definition = context.createStandardDefinition(type, this)
            definition.put(WireTypes.CLASS_KEYWORD, WireFormats.classOf(type))
            return CustomDefinition(
                definition,
                CustomDefinition.DefinitionType.STANDARD,
                CustomDefinition.AttributeInclusion.NO
            )
        }
    }

    /** Definitions keyed by the JVM binary name of their type, so the fragment's references find them. */
    private object BinaryNames : SchemaDefinitionNamingStrategy {
        override fun getDefinitionNameForKey(key: DefinitionKey, context: SchemaGenerationContext): String =
            name(key.type)

        fun name(type: ResolvedType): String =
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
            // A Wow enumeration is written by its wire names; a domain's, by its constants' names.
            if (rawClass in WowEnums.TYPES) {
                val values = rawClass.enumConstants.map { it as Enum<*> }.filter { it.name != UNKNOWN }
                return CustomDefinition(
                    mapper.createObjectNode().put("type", "string").apply {
                        putArray("enum").apply { values.forEach { add(WireNames.of(it)) } }
                    },
                )
            }
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
        /** The value a reader older than its writer reads an unknown value as, which no writer writes. */
        const val UNKNOWN = "UNKNOWN"
        const val DEFS = "\$defs"
        const val DESCRIPTION = "description"
        val PLATFORM_PACKAGES = listOf("java.", "javax.", "kotlin.")

        /** The body classes of the system commands, by their full names. */
        val SYSTEM_BODIES: Map<String, Class<*>> = Lifecycle.COMMANDS + ScheduleJobs.COMMANDS +
            mapOf(SnapshotRebuilds.REBUILD to RebuildSnapshot::class.java)

        private fun kotlinProperty(field: FieldScope) =
            if (field.declaringType.erasedType.isAnnotationPresent(Metadata::class.java)) {
                runCatching { field.rawMember.kotlinProperty }.getOrNull()
            } else {
                null
            }

        /**
         * A Kotlin property is required when it is non-null and no reader leaves it out: its constructor parameter has
         * no default, or it is not a constructor parameter (a property of the class body, which Jackson always writes).
         */
        fun isKotlinRequired(field: FieldScope): Boolean {
            val property = kotlinProperty(field) ?: return false
            val constructor = runCatching { field.declaringType.erasedType.kotlin.primaryConstructor }.getOrNull()
            val parameter = constructor?.parameters?.firstOrNull { it.name == field.declaredName }
                ?: return !property.returnType.isMarkedNullable
            return !parameter.isOptional && !parameter.type.isMarkedNullable
        }

        /** A type's `@Description`, else its `@Summary`; null without either, or with a blank one. */
        fun describe(type: Class<*>): String? =
            listOfNotNull(
                type.getAnnotation(Description::class.java)?.value,
                type.getAnnotation(Summary::class.java)?.value,
            ).map(String::trim).firstOrNull(String::isNotEmpty)

        fun isKotlinNullable(member: MemberScope<*, *>): Boolean = KotlinMembers.typeOf(member)?.isMarkedNullable == true
    }
}
