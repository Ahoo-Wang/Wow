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

import com.fasterxml.jackson.annotation.JsonValue
import com.github.victools.jsonschema.generator.FieldScope
import com.github.victools.jsonschema.generator.InstanceAttributeOverrideV2
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import com.github.victools.jsonschema.generator.TypeAttributeOverrideV2
import com.github.victools.jsonschema.generator.TypeScope
import me.ahoo.wow.api.query.annotation.MaskStrategy
import me.ahoo.wow.api.query.annotation.QueryAlias
import me.ahoo.wow.api.query.annotation.QueryDecimal
import me.ahoo.wow.api.query.annotation.QueryDuration
import me.ahoo.wow.api.query.annotation.QueryIndex
import me.ahoo.wow.api.query.annotation.QueryMoney
import me.ahoo.wow.api.query.annotation.QueryReference
import me.ahoo.wow.api.query.annotation.QueryTemporal
import me.ahoo.wow.api.query.annotation.Sensitive
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import tools.jackson.databind.node.ObjectNode
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Modifier
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.jvm.kotlinProperty

/**
 * The `x-wow-*` keywords `manifest-tools` writes into `$defs` from the query field annotations of `wow-query-api`,
 * so the query engine builds its query model from the Manifest without introspecting
 * classes at run time.
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
internal data class PropertyIndex(val path: String, val index: QueryIndex)

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
        check(type in states || type.getAnnotationsByType(QueryIndex::class.java).isEmpty()) {
            "@QueryIndex on ${type.name}: only an aggregate state class declares a query index."
        }
    }

    override fun overrideInstanceAttributes(
        attributes: ObjectNode,
        scope: FieldScope,
        context: SchemaGenerationContext,
    ) {
        if (scope.isFakeContainerItemScope) return
        val members = listOfNotNull<AnnotatedElement>(scope.rawMember, scope.findGetter()?.rawMember)
        val annotations = members.flatMap { it.annotations.asList() }
        val field = "${scope.declaringType.erasedType.name}.${scope.declaredName}"
        writeSensitive(attributes, scope, annotations, field)
        val aliases = annotations.filterIsInstance<QueryAlias>().flatMap { it.value.asList() }.distinct()
        if (aliases.isNotEmpty()) {
            attributes.putArray(QueryKeywords.ALIAS_KEYWORD).apply { aliases.forEach(::add) }
        }
        writeSemanticType(attributes, annotations, field)
        // A repeated @QueryIndex is held by its Kotlin-generated container, which getAnnotationsByType unwraps.
        collectIndexes(scope, members.flatMap { it.getAnnotationsByType(QueryIndex::class.java).asList() }, field)
    }

    private fun writeSemanticType(attributes: ObjectNode, annotations: List<Annotation>, field: String) {
        val declared = annotations.filter { annotation -> SEMANTIC_TYPES.any { it.isInstance(annotation) } }
        check(declared.size <= 1) {
            "A field has one semantic type: $field declares " +
                "${declared.joinToString(" and ") { "@${it.annotationClass.java.simpleName}" }}."
        }
        when (val annotation = declared.singleOrNull() ?: return) {
            is QueryTemporal -> {
                val temporal = attributes.putObject(QueryKeywords.TEMPORAL_KEYWORD)
                if (annotation.pattern.isEmpty()) {
                    temporal.put("unit", annotation.unit.name)
                } else {
                    check(annotation.unit == TimeUnit.MILLISECONDS) {
                        "@QueryTemporal on $field declares either a unit or a pattern, not both."
                    }
                    temporal.put("pattern", annotation.pattern)
                }
            }

            is QueryDecimal -> attributes.putObject(QueryKeywords.DECIMAL_KEYWORD).put("scale", annotation.scale)
            is QueryMoney -> attributes.putObject(QueryKeywords.MONEY_KEYWORD).apply {
                annotation.currency.takeIf { it.isNotEmpty() }?.let { put("currency", it) }
                annotation.currencyField.takeIf { it.isNotEmpty() }?.let { put("currencyField", it) }
                annotation.scale.takeIf { it >= 0 }?.let { put("scale", it) }
            }

            is QueryDuration -> attributes.putObject(QueryKeywords.DURATION_KEYWORD).put("unit", annotation.unit.name)
            is QueryReference -> attributes.putObject(QueryKeywords.REFERENCE_KEYWORD).apply {
                put("aggregateName", annotation.aggregateName)
                annotation.contextName.takeIf { it.isNotEmpty() }?.let { put("contextName", it) }
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
            listOf(annotation).filterIsInstance<Sensitive>() +
                annotation.annotationClass.java.annotations.filterIsInstance<Sensitive>()
        }.map { SensitiveRule.of(it, field) }.distinct()
        check(declared.size <= 1) { "$field declares several @Sensitive." }
        val own = declared.singleOrNull()
        if (own != null) {
            check(scope.type.erasedType == String::class.java || inherited != null) {
                "@Sensitive on $field: a sensitive field must have the String JVM type."
            }
            if (inherited != null) {
                check(own.level >= inherited.level) {
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

    private fun collectIndexes(scope: FieldScope, indexes: List<QueryIndex>, field: String) {
        if (indexes.isEmpty()) return
        val declaringType = scope.declaringType.erasedType
        check(declaringType in states) {
            "@QueryIndex on $field: only a property of an aggregate state class leads a query index; declare it on " +
                "the state class instead, with on = \"state.<path>\"."
        }
        indexes.forEach { index ->
            check(index.on.isEmpty()) {
                "@QueryIndex on $field: the property leads the index, so on stays empty; list further keys in with."
            }
        }
        val path = "state.${scope.schemaPropertyName}"
        propertyIndexes.getOrPut(declaringType, ::linkedMapOf)[path] = indexes.map { PropertyIndex(path, it) }
    }

    /** One effective `@Sensitive`: its level and mask. */
    private data class SensitiveRule(
        val level: SensitivityLevel,
        val keepPrefix: Int,
        val keepSuffix: Int,
        val strategy: Class<*>?,
    ) {
        companion object {
            fun of(sensitive: Sensitive, declarer: String): SensitiveRule {
                val keepPrefix = sensitive.mask.keepPrefix
                val keepSuffix = sensitive.mask.keepSuffix
                val strategy = sensitive.mask.strategy.java.takeIf { it != MaskStrategy::class.java }
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
                return SensitiveRule(sensitive.level, keepPrefix, keepSuffix, strategy)
            }

            private fun Class<*>.isInstantiable(): Boolean {
                if (runCatching { kotlin.objectInstance }.getOrNull() != null) return true
                if (!Modifier.isPublic(modifiers) || Modifier.isAbstract(modifiers) || isInterface) return false
                return constructors.any { it.parameterCount == 0 && Modifier.isPublic(it.modifiers) }
            }
        }
    }

    private companion object {
        val SEMANTIC_TYPES = listOf(
            QueryTemporal::class.java,
            QueryDecimal::class.java,
            QueryMoney::class.java,
            QueryDuration::class.java,
            QueryReference::class.java,
        )
        val PLATFORM_PACKAGES = listOf("java.", "javax.", "kotlin.")

        /**
         * The class the property's values are declared with: its Kotlin classifier (a value class erases to its
         * underlying type on the JVM; Kotlin reflection also types a Java field), unwrapped through maps,
         * collections and arrays down to their values.
         */
        fun FieldScope.valueType(): Class<*> {
            val kotlinType = runCatching { rawMember.kotlinProperty?.returnType }.getOrNull()
            return kotlinType?.valueClass() ?: type.erasedType
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

        /**
         * The `@Sensitive` of this class when it is a sensitive value type; a class carrying it must serialize as a
         * string: a Kotlin value class or a type with a `@JsonValue` member.
         */
        fun Class<*>.sensitiveValueType(): Sensitive? {
            if (isPrimitive || PLATFORM_PACKAGES.any { name.startsWith(it) }) return null
            val sensitive = getAnnotation(Sensitive::class.java) ?: return null
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
