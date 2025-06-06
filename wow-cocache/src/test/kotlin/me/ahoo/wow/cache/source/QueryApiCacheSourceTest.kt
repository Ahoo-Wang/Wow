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

package me.ahoo.wow.cache.source

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import me.ahoo.cache.DefaultCacheValue
import me.ahoo.wow.api.query.MaterializedSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class QueryApiCacheSourceTest {
    @Test
    fun load() {
        val queryApiCacheSource = spyk<QueryApiCacheSource<String>> {
            every { getById(any()) } returns mockk<MaterializedSnapshot<String>> {
                every { state } returns "test"
            }.toMono()
        }

        assertThat(queryApiCacheSource.loadCacheSourceConfiguration).isEqualTo(LoadCacheSourceConfiguration.DEFAULT)
        val cacheValue = queryApiCacheSource.loadCacheValue("test")
        assertThat(cacheValue).isEqualTo(DefaultCacheValue.forever("test"))
    }

    @Test
    fun loadWithTtl() {
        val queryApiCacheSource = spyk<QueryApiCacheSource<String>> {
            every {
                loadCacheSourceConfiguration
            } returns LoadCacheSourceConfiguration(ttl = 1000, ttlAmplitude = 0)
            every { getById(any()) } returns mockk<MaterializedSnapshot<String>> {
                every { state } returns "test"
            }.toMono()
        }
        assertThat(
            queryApiCacheSource.loadCacheSourceConfiguration
        ).isEqualTo(LoadCacheSourceConfiguration(ttl = 1000, ttlAmplitude = 0))
        val cacheValue = queryApiCacheSource.loadCacheValue("test")
        assertThat(cacheValue).isEqualTo(DefaultCacheValue.ttlAt("test", 1000))
    }

    @Test
    fun loadWithNull() {
        val queryApiCacheSource = spyk<QueryApiCacheSource<String>> {
            every { getById(any()) } returns Mono.empty()
        }

        val cacheValue = queryApiCacheSource.loadCacheValue("test")
        assertThat(cacheValue).isNull()
    }
}
