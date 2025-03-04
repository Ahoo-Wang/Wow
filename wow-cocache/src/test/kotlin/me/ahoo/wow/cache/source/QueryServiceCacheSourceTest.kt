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
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test
import reactor.kotlin.core.publisher.toMono

class QueryServiceCacheSourceTest {
    @Test
    fun load() {
        val snapshot = mockk<MaterializedSnapshot<String>> {
            every { state } returns "test"
        }
        val queryApiCacheSource = spyk<SnapshotQueryService<String>> {
            every { single(any()) } returns snapshot.toMono()
        }
        val queryServiceCacheSource = QueryServiceCacheSource(
            queryApiCacheSource,
            { it },
            LoadCacheSourceConfiguration.DEFAULT
        )

        val cacheValue = queryServiceCacheSource.loadCacheValue("test")
        assertThat(cacheValue, equalTo(DefaultCacheValue.forever("test")))
    }
}
