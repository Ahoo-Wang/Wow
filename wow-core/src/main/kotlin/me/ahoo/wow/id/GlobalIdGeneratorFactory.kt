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

package me.ahoo.wow.id

import me.ahoo.cosid.cosid.CosIdGenerator

/**
 * Factory interface for creating global ID generators.
 *
 * Primarily used for generating IDs for [me.ahoo.wow.api.messaging.Message.id].
 *
 * @see me.ahoo.wow.api.messaging.Message.id
 * @see me.ahoo.wow.api.annotation.Order
 */
fun interface GlobalIdGeneratorFactory {
    /**
     * Creates a global [CosIdGenerator] instance.
     *
     * Implementations should return a configured [CosIdGenerator] or null if they cannot provide one.
     *
     * @return the [CosIdGenerator] instance, or null if not available
     */
    fun create(): CosIdGenerator?
}
