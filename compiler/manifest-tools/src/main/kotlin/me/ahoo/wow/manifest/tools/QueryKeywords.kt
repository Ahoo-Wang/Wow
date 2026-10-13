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
import com.fasterxml.jackson.annotation.JsonValue
import com.github.victools.jsonschema.generator.FieldScope
import com.github.victools.jsonschema.generator.InstanceAttributeOverrideV2
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import com.github.victools.jsonschema.generator.TypeAttributeOverrideV2
import com.github.victools.jsonschema.generator.TypeScope
import me.ahoo.wow.manifest.tools.QueryAnnotations.MASK_STRATEGY
import me.ahoo.wow.manifest.tools.QueryAnnotations.QUERY_ALIAS
import me.ahoo.wow.manifest.tools.QueryAnnotations.QUERY_DECIMAL
import me.ahoo.wow.manifest.tools.QueryAnnotations.QUERY_DURATION
import me.ahoo.wow.manifest.tools.QueryAnnotations.QUERY_INDEX
import me.ahoo.wow.manifest.tools.QueryAnnotations.QUERY_MONEY
import me.ahoo.wow.manifest.tools.QueryAnnotations.QUERY_REFERENCE
import me.ahoo.wow.manifest.tools.QueryAnnotations.QUERY_TEMPORAL
import me.ahoo.wow.manifest.tools.QueryAnnotations.SENSITIVE
import tools.jackson.databind.node.ObjectNode
import java.lang.reflect.Modifier
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.jvm.kotlinProperty

/**
 * The `x-wow-*` keywords `manifest-tools` writes into `$defs` from the query field annotations of `wow-query-api`
 * (decisions D125, D126, D151), so the query engine builds its query model from the Manifest without introspecting
 * classes at run time (D37).
 *
 * A property keyword sits on the schema of the property that carries the annotation, in every definition of
 * `$defs` (a state, the types nested in it, an event payload), and keeps the annotation's parameters; a parameter
 * left at its "not given" default is omitted:
 *
 * | Keyword | Shape | From |
 * | --- | --- | --- |
 * | `x-wow-sensitive` | `{"level": "DISPLAY" \| "CONFIDENTIAL"}` | `@Sensitive` on the property, on an annotation class it carries, or on its value type |
 * | `x-wow-mask` | `{"keepPrefix": n, "keepSuffix": n}` or `{"strategy": "<binary class name>"}` | `@Sensitive.mask`; always next to `x-wow-sensitive` |
 * | `x-wow-alias` | `["state.oldName", …]` | `@QueryAlias` |
 * | `x-wow-temporal` | `{"unit": "<TimeUnit>"}` (epoch) or `{"pattern": "<DateTimeFormatter pattern>"}` | `@QueryTemporal` |
 * | `x-wow-decimal` | `{"scale": n}` | `@QueryDecimal` |
 * | `x-wow-money` | `{"currency": "CNY", "scale"?: n}` or `{"currencyField": "currency", "scale"?: n}` | `@QueryMoney` |
 * | `x-wow-duration` | `{"unit": "<TimeUnit>"}` | `@QueryDuration` |
 * | `x-wow-reference` | `{"aggregateName": "member", "contextName"?: "shop"}` | `@QueryReference` |
 *
 * They map 1:1 onto 9.x `wow-query`'s query schema: the sensitivity and mask onto its `MaskRule`, the alias paths
 * onto the field's aliases, and the semantic types onto `Temporal.Epoch(timeUnit)`, `Temporal.Formatted(pattern)`,
 * `NumericFormat.Decimal(scale)`, `NumericFormat.Money.of(currency, currencyField, scale)`, `TimeSpan(timeUnit)` and
 * `Reference(contextName, aggregateName)`, an omitted `contextName` being the declaring model's own context. Those
 * constructors keep validating the values (an ISO 4217 code, a scale range, a pattern); this build checks what only
 * the classes tell and what a keyword cannot express:
 *
 * - a property has at most one semantic type, and `@QueryTemporal` declares a unit or a pattern, not both;
 * - `@Sensitive` is declared at most once per property (directly or through an annotation class), only on a `String`
 *   property or one of a sensitive value type, never loosens its value type's level, and a class carrying it is a
 *   Kotlin value class or has a `@JsonValue` member;
 * - a mask keeps no negative count, a custom strategy keeps none and is a Kotlin `object` or a public class with a
 *   public no-argument constructor.
 *
 * The state schema's root carries the declared query indexes, see [QueryIndexes].
 */
internal object QueryKeywords {
    const val SENSITIVE_KEYWORD = "x-wow-sensitive"
    const val MASK_KEYWORD = "x-wow-mask"
    const val ALIAS_KEYWORD = "x-wow-alias"
    const val TEMPORAL_KEYWORD = "x-wow-temporal"
    const val DECIMAL_KEYWORD = "x-wow-decimal"
    const val MONEY_KEYWORD = "x-wow-money"
    const val DURATION_KEYWORD = "x-wow-duration"
    const val REFERENCE_KEYWORD = "x-wow-reference"
    const val INDEX_KEYWORD = "x-wow-index"
}

/** A `@QueryIndex` a state property leads: [path] is the property's logical path, `state.<its JSON name>`. */
internal data class PropertyIndex(val path: String, val index: AnnotationValues)

/**
 * Writes the query keywords of each property ([QueryKeywords]) and collects the `@QueryIndex` declarations of the
 * [states] properties. A `@QueryIndex` anywhere else fails the generation.
 */
internal class QueryFieldKeywords(private val states: Set<Class<*>>) :
    InstanceAttributeOverrideV2<FieldScope>,
    TypeAttributeOverrideV2 {
    /** The property indexes of each state class, keyed by the property path to collect each field once. */
    private val propertyIndexes = mutableMapOf<Class<*>, MutableMap<String, List<PropertyIndex>>>()

    fun propertyIndexes(state: Class<*>): List<PropertyIndex> = propertyIndexes[state].orEmpty().values.flatten()

    override fun overrideTypeAttributes(attributes: ObjectNode, scope: TypeScope, context: SchemaGenerationContext) {
        val type = scope.type.erasedType
        check(type in states || QueryAnnotations.named(type, QUERY_INDEX).isEmpty()) {
            "@QueryIndex on ${type.name}: only an aggregate state class declares a query index."
        }
    }

    override fun overrideInstanceAttributes(
        attributes: ObjectNode,
        scope: FieldScope,
        context: SchemaGenerationContext,
    ) {
        if (scope.isFakeContainerItemScope) return
        val annotations = scope.rawMember.annotations.asList() +
            scope.findGetter()?.rawMember?.annotations.orEmpty()
        val field = "${scope.declaringType.erasedType.name}.${scope.declaredName}"
        writeSensitive(attributes, scope, annotations, field)
        val aliases = QueryAnnotations.named(annotations, QUERY_ALIAS).flatMap { it.strings("value") }.distinct()
        if (aliases.isNotEmpty()) {
            attributes.putArray(QueryKeywords.ALIAS_KEYWORD).apply { aliases.forEach(::add) }
        }
        writeSemanticType(attributes, annotations, field)
        collectIndexes(scope, annotations, field)
    }

    private fun writeSemanticType(attributes: ObjectNode, annotations: List<Annotation>, field: String) {
        val declared = SEMANTIC_TYPES.flatMap { type -> QueryAnnotations.named(annotations, type) }
        check(declared.size <= 1) {
            "A field has one semantic type: $field declares ${declared.joinToString(" and ") { "@${it.simpleName}" }}."
        }
        val annotation = declared.singleOrNull() ?: return
        when (annotation.annotation.annotationClass.java.name) {
            QUERY_TEMPORAL -> {
                val temporal = attributes.putObject(QueryKeywords.TEMPORAL_KEYWORD)
                val pattern = annotation.string("pattern")
                val unit = annotation.enum("unit")
                if (pattern.isEmpty()) {
                    temporal.put("unit", unit.name)
                } else {
                    check(unit == TimeUnit.MILLISECONDS) {
                        "@QueryTemporal on $field declares either a unit or a pattern, not both."
                    }
                    temporal.put("pattern", pattern)
                }
            }

            QUERY_DECIMAL -> attributes.putObject(QueryKeywords.DECIMAL_KEYWORD).put("scale", annotation.int("scale"))
            QUERY_MONEY -> attributes.putObject(QueryKeywords.MONEY_KEYWORD).apply {
                annotation.string("currency").takeIf { it.isNotEmpty() }?.let { put("currency", it) }
                annotation.string("currencyField").takeIf { it.isNotEmpty() }?.let { put("currencyField", it) }
                annotation.int("scale").takeIf { it >= 0 }?.let { put("scale", it) }
            }

            QUERY_DURATION -> attributes.putObject(QueryKeywords.DURATION_KEYWORD)
                .put("unit", annotation.enum("unit").name)

            else -> attributes.putObject(QueryKeywords.REFERENCE_KEYWORD).apply {
                put("aggregateName", annotation.string("aggregateName"))
                annotation.string("contextName").takeIf { it.isNotEmpty() }?.let { put("contextName", it) }
            }
        }
    }

    private fun writeSensitive(
        attributes: ObjectNode,
        scope: FieldScope,
        annotations: List<Annotation>,
        field: String,
    ) {
        val valueType = scope.valueType()
        val inherited = valueType.sensitiveValueType()?.let { SensitiveRule.of(it, valueType.name) }
        val declared = annotations.flatMap { annotation ->
            QueryAnnotations.named(listOf(annotation), SENSITIVE) +
                QueryAnnotations.named(annotation.annotationClass.java, SENSITIVE)
        }.map { SensitiveRule.of(it, field) }.distinct()
        check(declared.size <= 1) { "$field declares several @Sensitive." }
        val own = declared.singleOrNull()
        if (own != null) {
            check(scope.type.erasedType == String::class.java || inherited != null) {
                "@Sensitive on $field: a sensitive field must have the String JVM type."
            }
            if (inherited != null) {
                check(own.level.ordinal >= inherited.level.ordinal) {
                    "@Sensitive on $field loosens its value type ${valueType.name} from ${inherited.level} to " +
                        "${own.level}."
                }
            }
        }
        val rule = own ?: inherited ?: return
        attributes.putObject(QueryKeywords.SENSITIVE_KEYWORD).put("level", rule.level.name)
        attributes.putObject(QueryKeywords.MASK_KEYWORD).apply {
            if (rule.strategy == null) {
                put("keepPrefix", rule.keepPrefix)
                put("keepSuffix", rule.keepSuffix)
            } else {
                put("strategy", rule.strategy.name)
            }
        }
    }

    private fun collectIndexes(scope: FieldScope, annotations: List<Annotation>, field: String) {
        val indexes = QueryAnnotations.named(annotations, QUERY_INDEX)
        if (indexes.isEmpty()) return
        val declaringType = scope.declaringType.erasedType
        check(declaringType in states) {
            "@QueryIndex on $field: only a property of an aggregate state class leads a query index; declare it on " +
                "the state class instead, with on = \"state.<path>\"."
        }
        indexes.forEach { index ->
            check(index.string("on").isEmpty()) {
                "@QueryIndex on $field: the property leads the index, so on stays empty; list further keys in with."
            }
        }
        val path = "state.${scope.schemaPropertyName}"
        propertyIndexes.getOrPut(declaringType, ::linkedMapOf)[path] = indexes.map { PropertyIndex(path, it) }
    }

    /** One effective `@Sensitive`: its level and mask. */
    private data class SensitiveRule(
        val level: Enum<*>,
        val keepPrefix: Int,
        val keepSuffix: Int,
        val strategy: Class<*>?,
    ) {
        companion object {
            fun of(sensitive: AnnotationValues, declarer: String): SensitiveRule {
                val mask = sensitive.annotation("mask")
                val keepPrefix = mask.int("keepPrefix")
                val keepSuffix = mask.int("keepSuffix")
                val strategy = mask.type("strategy").takeIf { it.name != MASK_STRATEGY }
                check(keepPrefix >= 0 && keepSuffix >= 0) {
                    "@Sensitive on $declarer: a mask keeps no negative count of code points."
                }
                if (strategy != null) {
                    check(keepPrefix == 0 && keepSuffix == 0) {
                        "@Sensitive on $declarer: a custom mask strategy keeps no prefix or suffix."
                    }
                    check(strategy.isInstantiable()) {
                        "@Sensitive on $declarer: the mask strategy ${strategy.name} must be a Kotlin object or a " +
                            "public class with a public no-argument constructor."
                    }
                }
                return SensitiveRule(sensitive.enum("level"), keepPrefix, keepSuffix, strategy)
            }

            private fun Class<*>.isInstantiable(): Boolean {
                if (runCatching { kotlin.objectInstance }.getOrNull() != null) return true
                if (!Modifier.isPublic(modifiers) || Modifier.isAbstract(modifiers) || isInterface) return false
                return constructors.any { it.parameterCount == 0 && Modifier.isPublic(it.modifiers) }
            }
        }
    }

    private companion object {
        val SEMANTIC_TYPES = listOf(QUERY_TEMPORAL, QUERY_DECIMAL, QUERY_MONEY, QUERY_DURATION, QUERY_REFERENCE)
        val PLATFORM_PACKAGES = listOf("java.", "javax.", "kotlin.")

        /**
         * The class the property's values are declared with: its Kotlin classifier (a value class erases to its
         * underlying type on the JVM), unwrapped through maps, collections and arrays down to their values.
         */
        fun FieldScope.valueType(): Class<*> {
            val kotlinType = runCatching { rawMember.kotlinProperty?.returnType }.getOrNull()
            return kotlinType?.valueClass() ?: type.valueClass()
        }

        fun KType.valueClass(): Class<*>? {
            var current: KType = this
            while (true) {
                val declared = (current.classifier as? KClass<*>)?.java ?: return null
                val contained = when {
                    Map::class.java.isAssignableFrom(declared) -> current.arguments.lastOrNull()?.type
                    Iterable::class.java.isAssignableFrom(declared) || declared.isArray ->
                        current.arguments.singleOrNull()?.type ?: return declared.componentType
                    else -> return declared
                }
                current = contained ?: return declared
            }
        }

        fun ResolvedType.valueClass(): Class<*> {
            var current = this
            while (true) {
                current = when {
                    current.isArray -> current.arrayElementType
                    Map::class.java.isAssignableFrom(current.erasedType) ->
                        current.typeParameters.lastOrNull() ?: return current.erasedType
                    Iterable::class.java.isAssignableFrom(current.erasedType) ->
                        current.typeParameters.singleOrNull() ?: return current.erasedType
                    else -> return current.erasedType
                }
            }
        }

        /**
         * The `@Sensitive` of this class when it is a sensitive value type; a class carrying it must serialize as a
         * string: a Kotlin value class or a type with a `@JsonValue` member.
         */
        fun Class<*>.sensitiveValueType(): AnnotationValues? {
            if (isPrimitive || PLATFORM_PACKAGES.any { name.startsWith(it) }) return null
            val sensitive = QueryAnnotations.named(this, SENSITIVE).singleOrNull() ?: return null
            check(runCatching { kotlin.isValue }.getOrDefault(false) || hasJsonValue()) {
                "@Sensitive on $name: only a value type, a Kotlin value class or a class with a @JsonValue member, " +
                    "may carry it."
            }
            return sensitive
        }

        fun Class<*>.hasJsonValue(): Boolean = generateSequence(this) { it.superclass }.any { type ->
            type.declaredMethods.any { it.isAnnotationPresent(JsonValue::class.java) } ||
                type.declaredFields.any { it.isAnnotationPresent(JsonValue::class.java) }
        }
    }
}
