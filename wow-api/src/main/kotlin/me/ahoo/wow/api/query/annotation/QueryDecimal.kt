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

package me.ahoo.wow.api.query.annotation

/**
 * Declares a numeric field a fixed-point decimal with [scale] fraction digits, so query consumers format and total it
 * at that precision: `@field:QueryDecimal(scale = 2)`. Never inferred: a `BigDecimal` does not tell its precision.
 * A field has one semantic type, so this cannot combine with [QueryTemporal] or [QueryMoney].
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class QueryDecimal(val scale: Int)

/**
 * Declares a numeric field an amount of money, in exactly one of a fixed ISO 4217 [currency] or the currency held by
 * the sibling string field [currencyField] (a property of the same object or element):
 *
 * - `@field:QueryMoney(currency = "CNY")`: [scale] defaults to the currency's standard fraction digits;
 * - `@field:QueryMoney(currencyField = "currency", scale = 2)`: [scale] is required.
 *
 * A field has one semantic type, so this cannot combine with [QueryTemporal] or [QueryDecimal].
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class QueryMoney(
    val currency: String = "",
    val currencyField: String = "",
    /** The fraction digits; negative, the default, takes the fixed currency's standard digits. */
    val scale: Int = -1,
)
