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

package me.ahoo.wow.query.dsl

/**
 * Represents the start of a range condition for a specific field.
 *
 * This class is used to define the starting point of a range in query conditions, typically
 * for operations that require a 'between' clause. The `field` parameter specifies the name of the
 * field on which the condition is applied, and `start` defines the starting value of the range.
 *
 * @param V The type of the value representing the start of the range.
 * @property field The name of the field to which this range condition applies.
 * @property start The value indicating the start of the range.
 */
data class BetweenStart<V>(val field: String, val start: V)
