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
 * Marks the member of an aggregate's state that derives the aggregate's ABAC tags from its state (V10, decision D220;
 * 9.x `StateAggregateTagsExtractor`), so query policies that filter by tags follow the state.
 *
 * The member is a function without parameters, or a property, of the state class, returning
 * `Map<String, Collection<String>>` (a `List` or a `Set` of values per tag key). It is pure: it reads the state only,
 * does no I/O and does not suspend. A state has at most one.
 *
 * ```kotlin
 * class OrderState {
 *     var country: String? = null
 *
 *     @Tags
 *     fun tags(): Map<String, List<String>> = mapOf("country" to listOfNotNull(country))
 * }
 * ```
 *
 * **Derived keys override the applied tags, per key.** The aggregate's tags are those the lifecycle command
 * [me.ahoo.wow.api.lifecycle.ApplyTags] last applied, with every key the function returns replaced by the value it
 * returns; a key it returns with no value removes that key. The function is evaluated again after every commit, and
 * whenever the aggregate is loaded, so a state change replaces a key's old values instead of adding to them (unlike
 * 9.x, which merged them). `ApplyTags` cannot set a key the function returns.
 *
 * The function owns the keys it returns, so it should **return every key it owns on every call**, with an empty list
 * for none: a key it stops returning is no longer derived, and keeps the value it was last derived with (the stored
 * tags carry it) until an `ApplyTags` leaves it out. Snapshots and state events carry these effective tags.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@MustBeDocumented
annotation class Tags
