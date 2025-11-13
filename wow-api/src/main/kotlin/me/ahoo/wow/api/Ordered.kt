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

package me.ahoo.wow.api

import me.ahoo.wow.api.annotation.Order

/**
 * Represents an entity that has an ordering attribute.
 *
 * This interface is used to define objects that can be ordered or sorted based on a specific [Order] value.
 * Implementations of this interface provide a way to determine the relative position or priority of instances
 * in collections or sequences.
 *
 * @property order The ordering value that determines the position or priority of this entity.
 *                 This property is read-only and must be implemented by classes that implement this interface.
 *                 The [Order] type encapsulates the ordering logic and comparison rules.
 */
interface Ordered {
    val order: Order
}
