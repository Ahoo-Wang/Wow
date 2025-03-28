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

package me.ahoo.wow.cache

interface CacheValueConfiguration {
    /**
     * 缓存过期时间，单位：秒
     *
     * 当为 `null` 时，表示不设置过期时间。
     *
     * @see me.ahoo.cache.api.TtlAt
     */
    val ttl: Long?

    /**
     * Jitter ttl (randomly) to prevent cache avalanche
     *
     * @see me.ahoo.cache.ComputedTtlAt.jitter
     */
    val amplitude: Long
}
