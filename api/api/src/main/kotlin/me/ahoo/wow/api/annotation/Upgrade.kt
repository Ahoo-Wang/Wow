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

import kotlin.reflect.KClass

/**
 * An upgrade of a domain event's stored JSON from revision [from] to revision `from + 1`.
 * An event at [Revision] `n` needs one upgrade from each of the revisions 1 to `n - 1`: the model processor
 * chains them, and a gap, a duplicate, an upgrade from 0 or less or from the current revision or beyond, or one of a
 * class that is not an event of an aggregate in the module, is a compile error.
 *
 * The function takes the payload as a Jackson `JsonNode` or `ObjectNode` (`tools.jackson.databind`) and returns the
 * upgraded one as either; it is pure, does no I/O and does not suspend. Since the generated model calls it with no
 * instance, it is a top-level function, or a member of an `object` (a companion object included) or a Java static
 * method, in the same module as the aggregate, and not private. A member of the aggregate root or of its state is not
 * accepted: put it in their companion object.
 *
 * ```kotlin
 * @Revision(2)
 * data class ItemAdded(val productId: String, val quantity: Int)
 *
 * @Upgrade(event = ItemAdded::class, from = 1)
 * fun quantityDefaultsToOne(node: ObjectNode): ObjectNode = node.put("quantity", 1)
 * ```
 *
 * @param event the event class.
 * @param from the revision this upgrades from, 1 or more and below the event's current revision.
 */
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class Upgrade(
    val event: KClass<*>,
    val from: Int,
)
