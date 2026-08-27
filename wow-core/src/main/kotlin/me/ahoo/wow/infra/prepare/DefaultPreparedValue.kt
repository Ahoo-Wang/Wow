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

package me.ahoo.wow.infra.prepare

import me.ahoo.wow.infra.prepare.PreparedValue.Companion.TTL_FOREVER

/**
 * Default implementation of PreparedValue that wraps a value with TTL information.
 * This class provides a simple data structure for storing prepared values with
 * optional expiration timestamps.
 *
 * @param V the type of the prepared value
 * @property value the actual value being prepared
 * @property ttlAt the expiration timestamp in milliseconds since epoch (defaults to permanent)
 */
data class DefaultPreparedValue<V>(
    override val value: V,
    override val ttlAt: Long = TTL_FOREVER
) : PreparedValue<V>
