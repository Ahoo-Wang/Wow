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
 * This field is prose a reader searches by words: a product's name or description, a note, a reason.
 *
 * A backend that indexes text by word indexes it so, and a `search` may name it; a `search` that names no field
 * searches the marked fields. Elasticsearch does so today; the other backends may ignore the marker. A field not
 * marked is matched by its whole value (equality, prefix, contains) and is not searched by words.
 *
 * It marks a `String` property, or a list or array of them; never a map, and never a field of a semantic type
 * ([QueryReference], [QueryTemporal], [QueryDecimal], [QueryMoney], [QueryDuration]), whose values are matched
 * exactly, nor a [Sensitive] one, whose words a search would reveal. It is not a semantic type itself: a field keeps
 * its one semantic type, and this marker combines with none.
 *
 * ```kotlin
 * data class ProductState(
 *     @field:QueryText
 *     val name: String,
 *     @field:QueryText
 *     val description: String,
 *     val categoryId: String,
 * )
 * ```
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class QueryText
