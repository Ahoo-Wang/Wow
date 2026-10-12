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

package me.ahoo.wow.api.annotation

/**
 * Former names of a command or event type (V10, decisions D22 and D55).
 *
 * Types are known by name, not by class: `context.aggregate.Name`, the class's simple name unless [Name] gives
 * another. Renaming a type changes that name, and the log keeps the old one in history. Listing the old name here
 * keeps that history readable: a stored event under a former name is read as this type, and a subscription that
 * filters on either name matches both. For a command, the log records the name with each commit, and a request
 * retried after the rename is still recognized as the same command.
 *
 * A value without a dot is a former name within the same aggregate (`context.aggregate.value`); a value with dots is
 * a full type name, for a type that moved from another aggregate or context. The model processor rejects a blank
 * value, a value with exactly one dot (neither form), a value equal to the type's current name, and a former name
 * that two types of the module claim.
 *
 * ```kotlin
 * @Name("ItemPut")
 * @Alias("ItemAdded")
 * data class ItemPut(val productId: String, val quantity: Int)
 * ```
 *
 * @param value the former names.
 */
@Target(AnnotationTarget.CLASS)
@MustBeDocumented
annotation class Alias(
    vararg val value: String
)
