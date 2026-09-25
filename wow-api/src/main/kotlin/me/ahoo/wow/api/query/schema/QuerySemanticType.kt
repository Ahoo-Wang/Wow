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

package me.ahoo.wow.api.query.schema

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.wow.api.query.QueryProtocol
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = QueryProtocol.Polymorphic.TYPE)
@JsonSubTypes(
    JsonSubTypes.Type(Temporal.Date::class, name = "TEMPORAL_DATE"),
    JsonSubTypes.Type(Temporal.Epoch::class, name = "TEMPORAL_EPOCH"),
    JsonSubTypes.Type(Temporal.Formatted::class, name = "TEMPORAL_FORMATTED"),
    JsonSubTypes.Type(NumericFormat.Decimal::class, name = "DECIMAL"),
    JsonSubTypes.Type(NumericFormat.Money::class, name = "MONEY"),
)
@Schema(
    oneOf = [
        Temporal.Date::class,
        Temporal.Epoch::class,
        Temporal.Formatted::class,
        NumericFormat.Decimal::class,
        NumericFormat.Money::class,
    ],
    discriminatorProperty = QueryProtocol.Polymorphic.TYPE,
)
interface QuerySemanticType

sealed interface Temporal : QuerySemanticType {
    @JsonTypeName("TEMPORAL_DATE")
    data object Date : Temporal

    @JsonTypeName("TEMPORAL_EPOCH")
    data class Epoch(
        val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
    ) : Temporal

    @JsonTypeName("TEMPORAL_FORMATTED")
    data class Formatted(
        val pattern: String,
    ) : Temporal {
        init {
            require(pattern.isNotBlank()) { "Temporal pattern cannot be blank." }
            DateTimeFormatter.ofPattern(pattern)
        }
    }
}

/**
 * How a numeric field is meant to be read (design §6.6): its decimal [scale], and for [Money] its currency. This is
 * display and totalling semantics for query consumers, never inferred from the JVM type, and it does not change how
 * the field is queried.
 */
sealed interface NumericFormat : QuerySemanticType {
    /** The number of digits after the decimal point. */
    val scale: Int

    /** A fixed-point decimal with [scale] fraction digits. */
    @JsonTypeName("DECIMAL")
    data class Decimal(override val scale: Int) : NumericFormat {
        init {
            requireScale(scale)
        }
    }

    /**
     * An amount of money in exactly one of a fixed ISO 4217 [currency] or the currency named by the sibling string
     * field [currencyField]. With a fixed currency, [scale] defaults to the currency's standard fraction digits
     * (`java.util.Currency.defaultFractionDigits`: 2 for CNY, 0 for JPY); with [currencyField] it must be given.
     */
    @JsonTypeName("MONEY")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Money(
        val currency: String? = null,
        val currencyField: String? = null,
        override val scale: Int,
    ) : NumericFormat {
        init {
            require((currency == null) != (currencyField == null)) {
                "MONEY names exactly one of currency and currencyField."
            }
            currency?.let(::standardCurrency)
            currencyField?.let {
                require(it.matches(PROPERTY_NAME)) { "MONEY currencyField [$it] must name a sibling property." }
            }
            requireScale(scale)
        }

        companion object {
            /** Resolves an omitted [scale]: a fixed [currency]'s standard fraction digits. */
            @JvmStatic
            @JsonCreator
            fun of(
                @JsonProperty("currency") currency: String? = null,
                @JsonProperty("currencyField") currencyField: String? = null,
                @JsonProperty("scale") scale: Int? = null,
            ): Money {
                require((currency == null) != (currencyField == null)) {
                    "MONEY names exactly one of currency and currencyField."
                }
                require(scale != null || currency != null) { "MONEY with a currencyField must declare its scale." }
                val resolved = scale ?: standardCurrency(checkNotNull(currency)).defaultFractionDigits
                require(resolved >= 0) { "MONEY currency [$currency] has no standard scale; declare one." }
                return Money(currency, currencyField, resolved)
            }

            private val PROPERTY_NAME = Regex("^@?[A-Za-z_][A-Za-z0-9_-]*$")

            private fun standardCurrency(code: String): java.util.Currency {
                val currency = try {
                    java.util.Currency.getInstance(code)
                } catch (error: IllegalArgumentException) {
                    throw IllegalArgumentException("MONEY currency [$code] is not an ISO 4217 code.", error)
                }
                require(currency.currencyCode == code) { "MONEY currency [$code] is not an ISO 4217 code." }
                return currency
            }
        }
    }

    companion object {
        /** The largest scale a decimal field may declare: the digits a 128-bit decimal holds. */
        const val MAX_SCALE: Int = 34

        private fun requireScale(scale: Int) {
            require(scale in 0..MAX_SCALE) { "Numeric scale must be within [0, $MAX_SCALE], but was [$scale]." }
        }
    }
}

/**
 * A field kept only for existing callers: it can still be queried, but new queries and view definitions should avoid
 * it. [message] says why, or what to use instead; omitted when the declaration gave none.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class QueryDeprecation(val message: String? = null)
