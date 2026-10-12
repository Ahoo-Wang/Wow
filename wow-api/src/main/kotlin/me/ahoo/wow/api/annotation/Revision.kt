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
 * The revision of a domain event's schema (V10, decisions D22 and D55). An event without it is at revision 1.
 *
 * The log stores each event with the revision it was written at. When the event's structure changes in a way old
 * records do not fit, raise the revision and give an upgrade from each earlier revision to the next one: records of
 * an older revision are read through that chain (design 01 §8). Adding an optional field with a default is not such
 * a change and needs no new revision.
 *
 * ```kotlin
 * @Revision(2)
 * data class ItemAdded(val productId: String, val quantity: Int)
 * ```
 *
 * Only events have revisions; a command is identified by its name alone.
 *
 * @param value the current revision, 1 or more.
 */
@Target(AnnotationTarget.CLASS)
@MustBeDocumented
annotation class Revision(
    val value: Int
)
