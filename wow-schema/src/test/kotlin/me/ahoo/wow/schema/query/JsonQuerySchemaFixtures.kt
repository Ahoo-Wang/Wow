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

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonUnwrapped
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.api.query.annotation.Mask
import me.ahoo.wow.api.query.annotation.MaskStrategy
import me.ahoo.wow.api.query.annotation.QueryTemporal
import me.ahoo.wow.api.query.annotation.Sensitive
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.query.schema.QuerySchemaConflictException
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.annotation.JsonSerialize
import tools.jackson.databind.ser.std.StdSerializer
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

@Schema(title = "State title", description = "State description")
internal data class StructuralState(
    @field:Schema(
        title = "Count title",
        description = "Count description",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val count: Int,
    val ratio: BigDecimal,
    val active: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val optional: String? = null,
    val status: StructuralStatus,
    val address: StructuralAddress,
    val items: List<StructuralItem>,
    val tags: List<String>,
)

internal enum class StructuralStatus { ACTIVE, INACTIVE }

internal data class DescriptiveMetadataState(
    @field:Schema(title = "Account status", description = "Account status description")
    val status: ReferencedStatus,
    @field:Schema(title = "Shared status", description = "Shared status description")
    val equalStatus: EqualReferencedStatus,
    val referencedStatus: ReferencedOnlyStatus,
)

@Summary("Bank account status enum")
@Description("Bank account status enum description")
internal enum class ReferencedStatus { OK, DISABLED }

@Summary("Shared status")
@Description("Shared status description")
internal enum class EqualReferencedStatus { OK, DISABLED }

@Summary("Referenced status")
@Description("Referenced status description")
internal enum class ReferencedOnlyStatus { OK, DISABLED }

internal data class StructuralAddress(val city: String)

internal data class StructuralItem(val quantity: Int)

internal data class JacksonState(
    @field:JsonProperty("display_name")
    val displayName: String,
    @field:JsonProperty("display.name")
    val dottedName: String,
    @field:JsonProperty("display name")
    val spacedName: String,
    @field:JsonProperty("0")
    val numericName: String,
    @field:JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @field:Sensitive(SensitivityLevel.DISPLAY)
    val secret: String,
    @field:JsonProperty(access = JsonProperty.Access.READ_ONLY)
    val visible: String,
    @get:JsonUnwrapped(prefix = "detail_", suffix = "_value")
    val details: JacksonDetails,
)

internal interface ComputedGetterContract {
    val version: Int

    @get:JsonIgnore
    val initialized: Boolean
        get() = version > 0

    val isRetryable: Boolean
        get() = version < 3

    val succeeded: Boolean
        get() = version == 1

    @JsonIgnore
    fun isEmpty(): Boolean = version == 0

    fun isEnabled(): Boolean = version > 0
}

internal data class ComputedGetterState(
    override val version: Int,
    val isActive: Boolean,
) : ComputedGetterContract

internal data class JacksonDetails(
    @field:JsonProperty("nested")
    val nested: String,
)

internal data class MaskedInvalidQueryFieldState(
    @field:JsonProperty("phone.number")
    @field:Sensitive(SensitivityLevel.DISPLAY) val phone: String,
)

internal data class CustomSerializerState(
    val typeValue: TypeCustomSerializedValue,
    @get:JsonSerialize(using = PropertyCustomSerializedValueSerializer::class)
    val propertyValue: PropertyCustomSerializedValue,
)

@JsonSerialize(using = TypeCustomSerializedValueSerializer::class)
internal data class TypeCustomSerializedValue(val hidden: String)

internal class TypeCustomSerializedValueSerializer : StdSerializer<TypeCustomSerializedValue>(
    TypeCustomSerializedValue::class.java,
) {
    override fun serialize(
        value: TypeCustomSerializedValue,
        generator: JsonGenerator,
        provider: SerializationContext,
    ) {
        generator.writeString(value.hidden)
    }
}

internal data class PropertyCustomSerializedValue(val hidden: String)

internal class PropertyCustomSerializedValueSerializer : StdSerializer<PropertyCustomSerializedValue>(
    PropertyCustomSerializedValue::class.java,
) {
    override fun serialize(
        value: PropertyCustomSerializedValue,
        generator: JsonGenerator,
        provider: SerializationContext,
    ) {
        generator.writeString(value.hidden)
    }
}

internal data class CompositionState(
    @field:Schema(allOf = [AllOfInherited::class])
    val allOf: AllOfValue,
    val anyOf: AnyOfValue,
    @field:Schema(oneOf = [OneOfFirst::class, OneOfSecond::class])
    val oneOf: OneOfValue,
    val payment: PaymentValue,
)

internal data class AllOfValue(val own: String)

internal data class AllOfInherited(val inherited: String)

@Schema(anyOf = [AnyOfLeft::class, AnyOfRight::class])
internal interface AnyOfValue

internal data class AnyOfLeft(val left: String) : AnyOfValue

internal data class AnyOfRight(val right: String) : AnyOfValue

internal class OneOfValue

internal data class OneOfFirst(val first: String)

internal data class OneOfSecond(val second: String)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = CardPayment::class, name = "card"),
    JsonSubTypes.Type(value = BankPayment::class, name = "bank"),
)
internal interface PaymentValue

internal data class CardPayment(val cardNumber: String) : PaymentValue

internal data class BankPayment(val account: String) : PaymentValue

internal data class RepeatedCompositionState(
    @field:Schema(oneOf = [StringValueBranch::class, IntegerValueBranch::class])
    val forward: RepeatedValue,
    @field:Schema(oneOf = [IntegerValueBranch::class, StringValueBranch::class])
    val reverse: RepeatedValue,
)

internal class RepeatedValue

internal data class StringValueBranch(val value: String)

internal data class IntegerValueBranch(val value: Int)

internal data class PartiallyRequiredCompositionState(
    @field:Schema(anyOf = [RequiredSharedValueBranch::class, OptionalSharedValueBranch::class])
    val value: RepeatedValue,
)

internal data class RequiredSharedValueBranch(val shared: String)

internal data class OptionalSharedValueBranch(
    @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val shared: String,
)

internal data class ConflictingAllOfValueTypesState(
    @field:Schema(allOf = [StringValueBranch::class, IntegerValueBranch::class])
    val value: RepeatedValue,
)

internal data class NumericSubtypeAllOfValueTypesState(
    @field:Schema(allOf = [IntegerValueBranch::class, DecimalValueBranch::class])
    val forward: RepeatedValue,
    @field:Schema(allOf = [DecimalValueBranch::class, IntegerValueBranch::class])
    val reverse: RepeatedValue,
)

internal data class DecimalValueBranch(val value: BigDecimal)

internal data class OpaqueAllOfValueTypesState(
    @field:Schema(allOf = [OpaqueValueBranch::class, StringValueBranch::class])
    val known: RepeatedValue,
    @field:Schema(allOf = [OpaqueValueBranch::class, SecondOpaqueValueBranch::class])
    val opaque: RepeatedValue,
)

internal data class OpaqueValueBranch(val value: TypeCustomSerializedValue)

internal data class SecondOpaqueValueBranch(val value: TypeCustomSerializedValue)

internal data class ConflictingCompositionState(
    @field:Schema(oneOf = [FirstTitledBranch::class, SecondTitledBranch::class])
    val union: RepeatedValue,
)

internal data class FirstTitledBranch(
    @field:Schema(title = "First")
    val value: String,
)

internal data class SecondTitledBranch(
    @field:Schema(title = "Second")
    val value: String,
)

internal data class ForwardMetadataState(
    @field:Schema(oneOf = [FirstMetadataBranch::class, SecondMetadataBranch::class])
    val value: RepeatedValue,
)

internal data class ReverseMetadataState(
    @field:Schema(oneOf = [SecondMetadataBranch::class, FirstMetadataBranch::class])
    val value: RepeatedValue,
)

@Schema(title = "First title", description = "First description")
internal class FirstMetadataBranch

@Schema(title = "Second title", description = "Second description")
internal class SecondMetadataBranch

internal data class ForwardEnumState(
    @field:Schema(oneOf = [FirstChoice::class, SecondChoice::class])
    val value: Any,
)

internal data class ReverseEnumState(
    @field:Schema(oneOf = [SecondChoice::class, FirstChoice::class])
    val value: Any,
)

internal enum class FirstChoice { FIRST, SHARED }

internal enum class SecondChoice { SECOND, SHARED }

internal data class EqualContainerMetadataState(
    @field:Schema(oneOf = [SharedMetadataFirst::class, SharedMetadataSecond::class])
    val metadata: Any,
    @field:Schema(oneOf = [LocalDate::class, Instant::class])
    val temporal: Any,
)

internal data class MixedTemporalAlternativeState(
    @field:Schema(anyOf = [LocalDate::class, String::class])
    val anyOf: Any,
    @field:Schema(oneOf = [LocalDate::class, String::class])
    val oneOf: Any,
)

@Schema(title = "Shared title", description = "Shared description")
internal class SharedMetadataFirst

@Schema(title = "Shared title", description = "Shared description")
internal class SharedMetadataSecond

internal data class RecursiveState(
    val name: String,
    val child: RecursiveState?,
    val children: List<RecursiveState>,
)

internal data class MaskedRecursiveState(
    @field:Sensitive(SensitivityLevel.DISPLAY) val secret: String,
    val child: MaskedRecursiveState?,
)

internal data class MutuallyRecursiveMaskedState(
    val node: MutuallyRecursiveMaskedNode,
)

internal data class MutuallyRecursiveMaskedNode(
    @field:Sensitive(SensitivityLevel.DISPLAY) val secret: String,
    val parent: MutuallyRecursiveMaskedState?,
)

internal data class DeepLevelOne(val two: DeepLevelTwo)

internal data class DeepLevelTwo(val three: DeepLevelThree)

internal data class DeepLevelThree(val four: DeepLevelFour)

internal data class DeepLevelFour(val five: DeepLevelFive)

internal data class DeepLevelFive(val six: DeepLevelSix)

internal data class DeepLevelSix(val value: String)

internal data class DynamicState(
    val attributes: Map<String, String>,
    val attributeGroups: List<Map<String, String>>,
    val closed: StructuralAddress,
)

internal data class MaskedDynamicState(
    val contacts: Map<String, MaskedContact>,
)

internal data class NativeTemporalState(
    val date: LocalDate,
    val instant: Instant,
    val instants: List<Instant>,
)

internal data class AnnotatedTemporalState(
    @field:JsonProperty("created_at")
    @field:QueryTemporal(unit = TimeUnit.SECONDS)
    val createdAt: Long,
    @field:QueryTemporal
    val timestamps: List<Long>,
)

internal data class InvalidTemporalState(
    @field:QueryTemporal
    val createdAt: String,
)

internal data class FormattedTemporalState(
    @field:QueryTemporal(pattern = "yyyy-MM-dd HH:mm:ss")
    val placedAt: String,
)

internal data class InvalidFormattedTemporalState(
    @field:QueryTemporal(pattern = "yyyy-MM-dd")
    val placedAt: Long,
)

internal data class AmbiguousTemporalState(
    @field:QueryTemporal(unit = TimeUnit.SECONDS, pattern = "yyyy-MM-dd")
    val placedAt: String,
)

internal data class MaskedStructuralState(
    @field:Sensitive(SensitivityLevel.DISPLAY) val password: String,
    val contacts: List<MaskedContact>,
    @get:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(keepPrefix = 1, keepSuffix = 1)) val getterSecret: String,
    @field:ComposedMask val composedSecret: String,
    @field:Sensitive(SensitivityLevel.CONFIDENTIAL) val confidentialSecret: String,
)

internal data class MaskedContact(
    @field:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(keepPrefix = 3, keepSuffix = 2)) val phone: String,
)

class PublicClassMaskStrategy : MaskStrategy {
    override fun mask(value: String): String = "masked-$value"
}

internal object ParentMaskStrategy : MaskStrategy {
    override fun mask(value: String): String = "parent-$value"
}

internal data class PublicClassStrategyState(
    @field:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(strategy = PublicClassMaskStrategy::class))
    val secret: String,
)

internal data class CustomStrategyWithEdgesState(
    @field:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(keepPrefix = 1, strategy = PublicClassMaskStrategy::class))
    val secret: String,
)

internal data class NegativeKeepState(
    @field:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(keepPrefix = -1))
    val secret: String,
)

private abstract class AbstractMaskStrategy : MaskStrategy

internal data class AbstractMaskStrategyState(
    @field:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(strategy = AbstractMaskStrategy::class))
    val secret: String,
)

internal val constructorMaskFailure = IllegalStateException("constructor failed")

internal class ThrowingMaskStrategy : MaskStrategy {
    init {
        throw constructorMaskFailure
    }

    override fun mask(value: String): String = value
}

internal data class ThrowingMaskStrategyState(
    @field:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(strategy = ThrowingMaskStrategy::class))
    val secret: String,
)

internal val constructorQuerySchemaFailure = QuerySchemaConflictException("constructor conflict")

internal class ConstructorQuerySchemaFailureMaskStrategy : MaskStrategy {
    init {
        throw constructorQuerySchemaFailure
    }

    override fun mask(value: String): String = value
}

internal data class ConstructorQuerySchemaFailureState(
    @field:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(strategy = ConstructorQuerySchemaFailureMaskStrategy::class))
    val secret: String,
)

internal val constructorError = AssertionError("constructor error")

internal class ConstructorErrorMaskStrategy : MaskStrategy {
    init {
        throw constructorError
    }

    override fun mask(value: String): String = value
}

internal data class ConstructorErrorState(
    @field:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(strategy = ConstructorErrorMaskStrategy::class))
    val secret: String,
)

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@Sensitive(SensitivityLevel.DISPLAY, mask = Mask(strategy = ParentMaskStrategy::class))
annotation class ParentMask

internal open class ParentPropertyMaskedState(
    @property:ParentMask
    open val inheritedSecret: String,
)

internal class ChildPropertyMaskedState(
    override val inheritedSecret: String,
) : ParentPropertyMaskedState(inheritedSecret)

internal interface GetterMaskedState {
    @get:Sensitive(SensitivityLevel.DISPLAY)
    val inheritedToken: String
}

internal data class InterfaceGetterMaskedState(
    override val inheritedToken: String,
) : GetterMaskedState

@Suppress("FunctionOnlyReturningConstant", "UnusedPrivateProperty")
internal open class NonPublicComputedGetterState {
    @get:Sensitive(SensitivityLevel.DISPLAY)
    private val privateSecret: String
        get() = "private"

    @get:Sensitive(SensitivityLevel.DISPLAY)
    protected val protectedSecret: String
        get() = "protected"
}

internal interface PrefixGetterMaskedState {
    @get:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(keepPrefix = 1))
    val inheritedToken: String
}

internal interface SuffixGetterMaskedState {
    @get:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(keepSuffix = 1))
    val inheritedToken: String
}

internal data class ConflictingInheritedGetterMaskedState(
    override val inheritedToken: String,
) : PrefixGetterMaskedState, SuffixGetterMaskedState

internal data class ReversedConflictingInheritedGetterMaskedState(
    override val inheritedToken: String,
) : SuffixGetterMaskedState, PrefixGetterMaskedState

internal interface BaseOverrideGetterMaskedState {
    @get:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(keepPrefix = 1))
    val inheritedToken: String
}

internal interface MidOverrideGetterMaskedState : BaseOverrideGetterMaskedState {
    @get:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(keepPrefix = 2))
    override val inheritedToken: String
}

internal data class MultiLevelOverrideGetterMaskedState(
    override val inheritedToken: String,
) : MidOverrideGetterMaskedState

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@Sensitive(SensitivityLevel.DISPLAY)
internal annotation class ComposedMask

internal data class ConflictingMaskAnnotationsState(
    @field:Sensitive(SensitivityLevel.DISPLAY)
    @get:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(keepPrefix = 1))
    val secret: String,
)

internal data class ConflictingLevelsState(
    @field:Sensitive(SensitivityLevel.DISPLAY)
    @get:Sensitive(SensitivityLevel.CONFIDENTIAL)
    val secret: String,
)

internal data class PartiallyMaskedAlternativeState(
    @field:Schema(oneOf = [MaskedStringBranch::class, UnmaskedStringBranch::class])
    val value: RepeatedValue,
)

internal data class DifferentlyMaskedAlternativeState(
    @field:Schema(oneOf = [MaskedStringBranch::class, KeptStringBranch::class])
    val value: RepeatedValue,
)

internal data class InvalidMaskedAlternativeState(
    @field:Schema(oneOf = [MaskedStringBranch::class, UnmaskedIntegerBranch::class])
    val value: RepeatedValue,
)

internal data class InvalidMaskedJvmTypeState(@field:Sensitive(SensitivityLevel.DISPLAY) val value: StructuralStatus)

internal data class MaskedStringBranch(@field:Sensitive(SensitivityLevel.DISPLAY) val shared: String)

internal data class KeptStringBranch(
    @field:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(keepPrefix = 1)) val shared: String,
)

internal data class UnmaskedStringBranch(val shared: String)

internal data class UnmaskedIntegerBranch(val shared: Int)
