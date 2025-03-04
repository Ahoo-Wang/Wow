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

import io.mockk.every
import io.mockk.spyk
import me.ahoo.cache.DefaultCacheValue
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class QueryApiCacheSourceTest {
    @Test
    fun load() {
        val queryApiCacheSource = spyk<QueryApiCacheSource<String>> {
            every { getStateById(any()) } returns "test".toMono()
        }
        assertThat(queryApiCacheSource.loadCacheSourceConfiguration, equalTo(LoadCacheSourceConfiguration.DEFAULT))
        val cacheValue = queryApiCacheSource.loadCacheValue("test")
        assertThat(cacheValue, equalTo(DefaultCacheValue.forever("test")))
    }

    @Test
    fun loadWithTtl() {
        val queryApiCacheSource = spyk<QueryApiCacheSource<String>> {
            every {
                loadCacheSourceConfiguration
            } returns LoadCacheSourceConfiguration(ttl = 1000)
            every { getStateById(any()) } returns "test".toMono()
        }
        assertThat(queryApiCacheSource.loadCacheSourceConfiguration, equalTo(LoadCacheSourceConfiguration(ttl = 1000)))
        val cacheValue = queryApiCacheSource.loadCacheValue("test")
        assertThat(cacheValue, equalTo(DefaultCacheValue.ttlAt("test", 1000)))
    }

    @Test
    fun loadWithNull() {
        val queryApiCacheSource = spyk<QueryApiCacheSource<String>> {
            every { getStateById(any()) } returns Mono.empty()
        }

        val cacheValue = queryApiCacheSource.loadCacheValue("test")
        assertThat(cacheValue, nullValue())
    }
}
