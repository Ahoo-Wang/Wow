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

package me.ahoo.wow.query.schema

/**
 * The `x-wow-*` keywords of a Manifest property schema (#4332, `QueryKeywords` of `manifest-tools`), each the 1:1
 * image of a `:query-api` annotation and of the 9.x schema declaration it stands for:
 *
 * | Keyword | Declaration |
 * | --- | --- |
 * | `x-wow-sensitive` `{level}` | [MaskRule.level] |
 * | `x-wow-mask` `{keepPrefix, keepSuffix}` / `{strategy}` | [MaskRule.mask] ([QueryMask.Keep] / [QueryMask.Strategy]) |
 * | `x-wow-alias` `[path, ...]` | [QueryFieldDeclaration.aliases] |
 * | `x-wow-temporal` `{unit}` / `{pattern}` | [Temporal.Epoch] / [Temporal.Formatted] |
 * | `x-wow-decimal` `{scale}` | [NumericFormat.Decimal] |
 * | `x-wow-money` `{currency \| currencyField, scale?}` | [NumericFormat.Money.of] |
 * | `x-wow-duration` `{unit}` | [TimeSpan] |
 * | `x-wow-reference` `{aggregate, context?}` | [Reference] (the declaring context when omitted) |
 * | `x-wow-text` `{}` | [QueryFieldDeclaration.text], on each string leaf (an array's items, a union's strings) |
 * | `x-wow-index` (state root) | informational: the storage's index DDL reads it, the query schema does not |
 */
internal object ManifestQueryKeywords {
    const val SENSITIVE: String = "x-wow-sensitive"
    const val MASK: String = "x-wow-mask"
    const val ALIAS: String = "x-wow-alias"
    const val TEMPORAL: String = "x-wow-temporal"
    const val DECIMAL: String = "x-wow-decimal"
    const val MONEY: String = "x-wow-money"
    const val DURATION: String = "x-wow-duration"
    const val REFERENCE: String = "x-wow-reference"
    const val TEXT: String = "x-wow-text"
    const val INDEX: String = "x-wow-index"
}
