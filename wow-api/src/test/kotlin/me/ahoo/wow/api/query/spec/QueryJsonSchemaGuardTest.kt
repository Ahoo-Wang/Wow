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

package me.ahoo.wow.api.query.spec

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.QueryField
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaGetter

/**
 * Guards the hand-written, published `schema/query/v2` request schemas against the wire contract: every
 * [FilterOperator] has exactly one schema branch, and each branch's shape follows the operator's [FilterOperatorSpec]
 * and the Jackson-decoded filter class. Adding an operator or changing a filter's properties fails here until the
 * published schema is updated.
 */
class QueryJsonSchemaGuardTest {
    private val jsonMapper = jacksonObjectMapper()
    private val schema = readSchema("filter-expression")
    private val definitions = schema.path(DEFINITIONS)

    private val filterClasses: Map<String, KClass<*>> =
        FilterExpression::class.java.getAnnotation(JsonSubTypes::class.java).value
            .associate { it.name to it.value }

    private val rootBranches = branchesOf("filterExpression")
    private val elementBranches = branchesOf("elementPredicate")

    @Test
    fun `filter expression branches should cover every operator exactly once`() {
        val operators = FilterOperator.entries.map { it.name }
        rootBranches.map { it.first }.assert().containsExactlyInAnyOrderElementsOf(operators)
        filterClasses.keys.assert().containsExactlyInAnyOrderElementsOf(operators)
    }

    @Test
    fun `element predicate branches should cover every operator an element scope accepts`() {
        val expected = FilterOperator.entries
            .filter { it.spec.target != OperatorTarget.SYSTEM_FIELD && it.spec.target != OperatorTarget.MODEL_OR_FIELDS }
            .map { it.name }
        elementBranches.map { it.first }.assert().containsExactlyInAnyOrderElementsOf(expected)
    }

    @TestFactory
    fun `filter expression branch should match the operator spec and wire class`(): List<DynamicTest> =
        FilterOperator.entries.map { operator ->
            DynamicTest.dynamicTest(operator.name) {
                val branch = rootBranches.single { it.first == operator.name }.second
                verifyBranch(operator, branch, elementScope = false)
                elementBranches.singleOrNull { it.first == operator.name }?.let {
                    verifyBranch(operator, it.second, elementScope = true)
                }
            }
        }

    @Test
    fun `scalar constraints should follow the decoder`() {
        definitions.path("queryField").path("pattern").text().assert().isEqualTo(QueryField.PATTERN)

        decodes(mapOf(OP to FilterOperator.SEARCH.name, "query" to " ")).assert().isFalse()
        definitions.path("search").path("properties").path("query").path("pattern").text().assert()
            .isEqualTo(NON_BLANK_PATTERN)
    }

    @Test
    fun `query schemas should reference the filter expression schema`() {
        listOf("single-query", "list-query", "paged-query", "cursor-query").forEach { name ->
            val query = readSchema(name)
            query.path("properties").path("filter").path(REF).text().assert().isEqualTo(FILTER_SCHEMA_REF)
            query.path("required").toList().map { it.asString() }.assert().contains("filter")
        }
        readSchema("count-query").path(REF).text().assert().isEqualTo(FILTER_SCHEMA_REF)
    }

    private fun verifyBranch(operator: FilterOperator, branch: JsonNode, elementScope: Boolean) {
        val spec = operator.spec
        val properties = branch.path("properties")
        val propertyNames = properties.properties().map { it.key }.toSet()
        val required = branch.path("required").toList().map { it.asString() }.toSet()
        branch.path("type").text().assert().isEqualTo("object")
        branch.path("additionalProperties").asBoolean().assert().isFalse()

        val wireParameters = wireParametersOf(operator)
        propertyNames.assert().isEqualTo(wireParameters.keys + OP)
        required.assert().isEqualTo(wireParameters.filterValues { it.isRequired }.keys + OP)
        wireParameters.forEach { (name, parameter) ->
            verifyType(operator, name, properties.path(name), parameter.type, elementScope)
        }

        val hasField = FIELD in propertyNames
        when (spec.target) {
            OperatorTarget.NONE -> propertyNames.assert().containsExactly(OP)
            OperatorTarget.LOGICAL -> {
                hasField.assert().isFalse()
                propertyNames.assert().isEqualTo(setOf(OP, OPERANDS))
            }
            OperatorTarget.SYSTEM_FIELD -> {
                hasField.assert().isFalse()
                spec.systemField.assert().isNotNull()
            }
            OperatorTarget.FIELD -> {
                required.assert().contains(FIELD)
                properties.path(FIELD).path(REF).text().assert().isEqualTo(definitionRef("queryField"))
            }
            OperatorTarget.MODEL_OR_FIELDS -> {
                hasField.assert().isFalse()
                required.assert().doesNotContain("fields")
                properties.path("fields").path("items").path(REF).text().assert()
                    .isEqualTo(definitionRef("queryField"))
            }
        }
        val valueProperties = propertyNames - setOf(OP, FIELD)
        when (spec.valueRule) {
            ValueRule.DOMAIN -> valueProperties.assert().isNotEmpty().allSatisfy {
                it.assert().isIn(VALUE, VALUES, "lowerBound", "upperBound")
            }
            ValueRule.COLLECTION_DOMAIN -> valueProperties.assert().containsExactly(VALUES)
            ValueRule.COLLECTION, ValueRule.SINGLE_STRING -> valueProperties.assert().isEmpty()
            ValueRule.TEMPORAL -> valueProperties.assert().contains("zoneId", "datePattern", "timeUnit")
            ValueRule.ELEMENT_SCOPE -> properties.path("predicate").path(REF).text().assert()
                .isEqualTo(definitionRef("elementPredicate"))
            ValueRule.NONE -> Unit
        }
        verifyDecodedBoundaries(operator, branch, wireParameters)
    }

    private fun verifyType(
        operator: FilterOperator,
        name: String,
        property: JsonNode,
        type: KType,
        elementScope: Boolean,
    ) {
        val classifier = type.classifier as KClass<*>
        val ref = property.path(REF).text()
        val resolved = resolve(property)
        val context = "$operator.$name"
        when {
            classifier == String::class -> resolved.path("type").text().assert().describedAs(context)
                .isEqualTo("string")
            classifier == Int::class -> resolved.path("type").text().assert().describedAs(context)
                .isEqualTo("integer")
            classifier == QueryField::class -> ref.assert().describedAs(context).isEqualTo(definitionRef("queryField"))
            classifier == JsonNode::class -> ref.assert().describedAs(context)
                .isIn(definitionRef("literal"), definitionRef("comparableLiteral"))
            classifier == FilterExpression::class -> ref.assert().describedAs(context).isEqualTo(
                definitionRef(
                    if (elementScope || operator == FilterOperator.ELEMENT_MATCH) "elementPredicate" else "filterExpression"
                )
            )
            classifier.isSubclassOf(Enum::class) -> resolved.path("enum").toList().map { it.asString() }.assert()
                .describedAs(context)
                .containsExactlyElementsOf(classifier.java.enumConstants.map { (it as Enum<*>).name })
            classifier.isSubclassOf(Collection::class) -> {
                resolved.path("type").text().assert().describedAs(context).isEqualTo("array")
                resolved.path("uniqueItems").asBoolean().assert().describedAs(context)
                    .isEqualTo(classifier.isSubclassOf(Set::class))
                val itemType = requireNotNull(type.arguments.single().type)
                verifyType(operator, "$name[]", resolved.path("items"), itemType, elementScope)
            }
            else -> error("$context has a wire type [$type] this guard does not map to JSON Schema yet.")
        }
    }

    /**
     * Where a branch carries free-form values or collections, the decoder's boundaries are the truth: a value
     * branch admits `null` only when the decoder does, and a collection requires an item only when the decoder does.
     */
    private fun verifyDecodedBoundaries(
        operator: FilterOperator,
        branch: JsonNode,
        wireParameters: Map<String, WireParameter>,
    ) {
        val probed = wireParameters.filter { (_, parameter) ->
            val classifier = parameter.type.classifier as KClass<*>
            classifier == JsonNode::class || classifier.isSubclassOf(Collection::class)
        }
        if (probed.isEmpty()) return
        val baseline = wireParameters.filterValues { it.isRequired }
            .mapValues { (_, parameter) -> sampleOf(parameter.type) } + (OP to operator.name)
        decodes(baseline).assert().describedAs("$operator baseline").isTrue()
        probed.forEach { (name, parameter) ->
            val property = branch.path("properties").path(name)
            val context = "$operator.$name"
            if (parameter.type.classifier == JsonNode::class) {
                val nullable = decodes(baseline + (name to null))
                property.path(REF).text().assert().describedAs(context)
                    .isEqualTo(definitionRef(if (nullable) "literal" else "comparableLiteral"))
            } else {
                val emptyAccepted = decodes(baseline + (name to emptyList<Any>()))
                resolve(property).path("minItems").asInt().assert().describedAs(context)
                    .isEqualTo(if (emptyAccepted) 0 else 1)
                val itemType = parameter.type.arguments.single().type
                if (itemType?.classifier == JsonNode::class) {
                    val nullItemAccepted = decodes(baseline + (name to listOf(null)))
                    resolve(property).path("items").path(REF).text().assert().describedAs(context)
                        .isEqualTo(definitionRef(if (nullItemAccepted) "literal" else "comparableLiteral"))
                }
            }
        }
    }

    private fun decodes(payload: Map<String, Any?>): Boolean = runCatching {
        jsonMapper.readValue(jsonMapper.writeValueAsString(payload), FilterExpression::class.java)
    }.isSuccess

    private fun sampleOf(type: KType): Any {
        val classifier = type.classifier as KClass<*>
        return when {
            classifier == String::class -> "sample"
            classifier == Int::class -> 1
            classifier == QueryField::class -> "field"
            classifier == JsonNode::class -> 1
            classifier == FilterExpression::class -> mapOf(OP to FilterOperator.MATCH_ALL.name)
            classifier.isSubclassOf(Enum::class) -> (classifier.java.enumConstants.first() as Enum<*>).name
            classifier.isSubclassOf(Collection::class) -> listOf(sampleOf(requireNotNull(type.arguments.single().type)))
            else -> error("No sample for [$type].")
        }
    }

    private fun wireParametersOf(operator: FilterOperator): Map<String, WireParameter> {
        val filterClass = requireNotNull(filterClasses[operator.name]) { "No filter class for [$operator]." }
        if (filterClass.objectInstance != null) return emptyMap()
        val properties = filterClass.memberProperties.associateBy { it.name }
        return requireNotNull(filterClass.primaryConstructor).parameters
            .mapNotNull { parameter ->
                val getter = properties[parameter.name]?.javaGetter
                if (getter?.isAnnotationPresent(JsonIgnore::class.java) == true) return@mapNotNull null
                val wireName = getter?.getAnnotation(JsonProperty::class.java)?.value ?: requireNotNull(parameter.name)
                wireName to WireParameter(parameter)
            }
            .toMap()
    }

    private class WireParameter(parameter: KParameter) {
        val type: KType = parameter.type
        val isRequired: Boolean = !parameter.isOptional && !parameter.type.isMarkedNullable
    }

    /** The `(operator, branch)` pairs of a `oneOf` definition, with `$ref` chains resolved. */
    private fun branchesOf(definition: String): List<Pair<String, JsonNode>> =
        definitions.path(definition).path("oneOf").toList().map { reference ->
            val branch = resolve(reference)
            val operators = branch.path("properties").path(OP).path("enum").toList().map { it.asString() }
            operators.assert().describedAs(reference.toString()).hasSize(1)
            operators.single() to branch
        }

    private fun resolve(node: JsonNode): JsonNode {
        var current = node
        while (current.has(REF)) {
            val ref = requireNotNull(current.path(REF).text())
            require(ref.startsWith(DEFINITION_PREFIX)) { "Unexpected reference [$ref]." }
            current = definitions.path(ref.removePrefix(DEFINITION_PREFIX))
            require(!current.isMissingNode) { "Unresolved reference [$ref]." }
        }
        return current
    }

    private fun readSchema(name: String): JsonNode =
        jsonMapper.readTree(Files.readString(Path.of("$SCHEMA_DIRECTORY/$name.schema.json")))

    companion object {
        private const val SCHEMA_DIRECTORY = "../schema/query/v2"
        private const val FILTER_SCHEMA_REF = "filter-expression.schema.json"
        private const val DEFINITIONS = "definitions"
        private const val DEFINITION_PREFIX = "#/$DEFINITIONS/"
        private const val REF = "\$ref"
        private const val OP = "op"
        private const val OPERANDS = "operands"
        private const val FIELD = "field"
        private const val VALUE = "value"
        private const val VALUES = "values"
        private const val NON_BLANK_PATTERN = "\\S"

        private fun definitionRef(name: String) = "$DEFINITION_PREFIX$name"

        private fun JsonNode.text(): String? = if (isString) stringValue() else null
    }
}
