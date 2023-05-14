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
package me.ahoo.wow.infra.idempotency

import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import java.time.Duration

class BloomFilterIdempotencyCheckerTest {
    private val idempotencyChecker = BloomFilterIdempotencyChecker(Duration.ofSeconds(1)
    ) {
        BloomFilter.create(Funnels.stringFunnel(Charsets.UTF_8), 10)
    }

    @Test
    fun check() {
        MatcherAssert.assertThat(idempotencyChecker.check("hi").block(), Matchers.equalTo(true))
        MatcherAssert.assertThat(idempotencyChecker.check("hi").block(), Matchers.equalTo(false))
    }
}
