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

package me.ahoo.wow.schema.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.example.domain.cart.CartState
import me.ahoo.wow.example.domain.order.OrderState
import me.ahoo.wow.query.schema.QueryMemberFact
import me.ahoo.wow.query.schema.QueryTypeFact
import me.ahoo.wow.schema.KotlinFixture
import me.ahoo.wow.schema.TestState
import me.ahoo.wow.schema.TreeNodeFixture
import me.ahoo.wow.schema.golden.Golden
import me.ahoo.wow.schema.query.maskfixture.privateMaskStrategyStateType
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.util.Objects

/**
 * Snapshots the [QueryTypeFact] that [JsonQueryModelSource] reports for every query fixture, including the ones it
 * rejects (their golden holds the error).
 */
class QueryFactGoldenTest {
    private val types: List<Class<*>> = listOf(
        AbstractMaskStrategyState::class.java,
        AmbiguousTemporalState::class.java,
        AnnotatedTemporalState::class.java,
        ChildPropertyMaskedState::class.java,
        CompositionState::class.java,
        ComputedGetterState::class.java,
        ConflictingAllOfValueTypesState::class.java,
        ConflictingCompositionState::class.java,
        ConflictingInheritedGetterMaskedState::class.java,
        ConflictingLevelsState::class.java,
        ConflictingMaskAnnotationsState::class.java,
        ConstructorErrorState::class.java,
        ConstructorQuerySchemaFailureState::class.java,
        ContactState::class.java,
        ContainerValueTypeState::class.java,
        CustomSerializerState::class.java,
        CustomStrategyWithEdgesState::class.java,
        DescriptiveMetadataState::class.java,
        DifferentlyMaskedAlternativeState::class.java,
        DurationReferenceState::class.java,
        DynamicState::class.java,
        EqualContainerMetadataState::class.java,
        FormattedTemporalState::class.java,
        ForwardEnumState::class.java,
        ForwardMetadataState::class.java,
        InterfaceGetterMaskedState::class.java,
        InvalidAliasState::class.java,
        InvalidFormattedTemporalState::class.java,
        InvalidMaskedAlternativeState::class.java,
        InvalidMaskedJvmTypeState::class.java,
        InvalidTemporalState::class.java,
        JacksonState::class.java,
        LegacyGetterMaskedState::class.java,
        LegacyMaskedState::class.java,
        LegacyTemporalState::class.java,
        LoosenedValueTypeState::class.java,
        MaskedDynamicState::class.java,
        MaskedInvalidQueryFieldState::class.java,
        MaskedRecursiveState::class.java,
        MaskedStructuralState::class.java,
        MixedTemporalAlternativeState::class.java,
        MultiLevelOverrideGetterMaskedState::class.java,
        MutuallyRecursiveMaskedState::class.java,
        NativeTemporalState::class.java,
        NegativeKeepState::class.java,
        NonValueTypeState::class.java,
        NumericFormatState::class.java,
        NumericSubtypeAllOfValueTypesState::class.java,
        OpaqueAllOfValueTypesState::class.java,
        PartiallyMaskedAlternativeState::class.java,
        PartiallyRequiredCompositionState::class.java,
        PublicClassStrategyState::class.java,
        RecursiveState::class.java,
        RenamedState::class.java,
        RepeatedCompositionState::class.java,
        ReverseEnumState::class.java,
        ReverseMetadataState::class.java,
        ReversedConflictingInheritedGetterMaskedState::class.java,
        StructuralState::class.java,
        ThrowingMaskStrategyState::class.java,
        ValueTypeState::class.java,
        privateMaskStrategyStateType(),
        TestState::class.java,
        KotlinFixture::class.java,
        TreeNodeFixture::class.java,
        MockStateAggregate::class.java,
        OrderState::class.java,
        CartState::class.java,
    )

    @Test
    fun `query facts match goldens`() {
        val source = JsonQueryModelSource()
        val mismatches = types.mapNotNull { type ->
            val output = runCatching { source.describe(type).toJson() }.getOrElse { error ->
                JsonSerializer.createObjectNode()
                    .put("error", error.javaClass.name)
                    .put("message", error.message)
            }
            Golden.compare("query/${type.simpleName}.json", output.toPrettyString() + "\n")
        }
        mismatches.assert().isEmpty()
    }

    private fun QueryTypeFact.toJson(): JsonNode = JsonSerializer.valueToTree(toMap())

    /** Absent and empty facts are left out so a golden shows only what the source stated. */
    private fun QueryTypeFact.toMap(): Map<String, Any> = linkedMapOf(
        "kind" to kind.name,
        "valueTypes" to valueTypes.map { it.value },
        "nullable" to nullable,
        "required" to required,
        "enumValues" to enumValues,
        "title" to title,
        "description" to description,
        "formats" to formats,
        "properties" to properties.mapValues { (_, fact) -> fact.toMap() },
        "items" to items?.toMap(),
        "additionalProperties" to additionalProperties?.toMap(),
        "alternatives" to alternatives.map { it.toMap() },
        "member" to member?.toMap(),
        "omitted" to omitted.map { it.toMap() },
    ).filterValues { value -> value != null && (value as? Collection<*>)?.isEmpty() != true && (value as? Map<*, *>)?.isEmpty() != true }
        .mapValues { (_, value) -> value!! }

    private fun QueryMemberFact.toMap(): Map<String, Any> = linkedMapOf(
        "name" to name,
        "type" to type.name,
        "valueType" to valueType.name,
        "annotations" to annotations.map { it.render() },
    )

    /** `@Type(member=value, …)` with only non-default members, sorted, because [Annotation.toString] order varies. */
    private fun Annotation.render(): String {
        val members = annotationClass.java.declaredMethods.sortedBy { it.name }.mapNotNull { method ->
            val value = method.invoke(this)
            if (method.defaultValue != null && Objects.deepEquals(value, method.defaultValue)) {
                null
            } else {
                "${method.name}=${value.renderValue()}"
            }
        }
        return "@${annotationClass.java.name}(${members.joinToString()})"
    }

    private fun Any?.renderValue(): String = when (this) {
        is Annotation -> render()
        is Class<*> -> "$name.class"
        is Array<*> -> joinToString(prefix = "{", postfix = "}") { it.renderValue() }
        is IntArray -> joinToString(prefix = "{", postfix = "}")
        is String -> "\"$this\""
        else -> toString()
    }
}
