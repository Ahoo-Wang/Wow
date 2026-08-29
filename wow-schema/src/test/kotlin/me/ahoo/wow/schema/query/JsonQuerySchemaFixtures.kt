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

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonUnwrapped
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.wow.api.query.mask.KeepMask
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryTemporal
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
    val secret: String,
    @field:JsonProperty(access = JsonProperty.Access.READ_ONLY)
    val visible: String,
    @get:JsonUnwrapped(prefix = "detail_", suffix = "_value")
    val details: JacksonDetails,
)

internal data class JacksonDetails(
    @field:JsonProperty("nested")
    val nested: String,
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
    val value: RepeatedValue,
)

internal data class ReverseEnumState(
    @field:Schema(oneOf = [SecondChoice::class, FirstChoice::class])
    val value: RepeatedValue,
)

internal enum class FirstChoice { FIRST, SHARED }

internal enum class SecondChoice { SECOND, SHARED }

internal data class EqualContainerMetadataState(
    @field:Schema(oneOf = [SharedMetadataFirst::class, SharedMetadataSecond::class])
    val metadata: RepeatedValue,
    @field:Schema(oneOf = [LocalDate::class, Instant::class])
    val temporal: RepeatedValue,
)

internal data class MixedTemporalAlternativeState(
    @field:Schema(anyOf = [LocalDate::class, String::class])
    val anyOf: RepeatedValue,
    @field:Schema(oneOf = [LocalDate::class, String::class])
    val oneOf: RepeatedValue,
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

internal data class NativeTemporalState(
    val date: LocalDate,
    val instant: Instant,
    val instants: List<Instant>,
)

internal data class AnnotatedTemporalState(
    @field:JsonProperty("created_at")
    @field:QueryTemporal(TimeUnit.SECONDS)
    val createdAt: Long,
    @field:QueryTemporal
    val timestamps: List<Long>,
)

internal data class InvalidTemporalState(
    @field:QueryTemporal
    val createdAt: String,
)

internal data class MaskedStructuralState(
    @field:Mask val password: String,
    val contacts: List<MaskedContact>,
    @get:KeepMask(prefix = 1, suffix = 1) val getterSecret: String,
    @field:ComposedMask val composedSecret: String,
)

internal data class MaskedContact(
    @field:KeepMask(prefix = 3, suffix = 2) val phone: String,
)

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@Mask
internal annotation class ComposedMask

internal data class ConflictingMaskAnnotationsState(
    @field:Mask
    @get:KeepMask(prefix = 1)
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

internal data class MaskedStringBranch(@field:Mask val shared: String)

internal data class KeptStringBranch(@field:KeepMask(prefix = 1) val shared: String)

internal data class UnmaskedStringBranch(val shared: String)

internal data class UnmaskedIntegerBranch(val shared: Int)
