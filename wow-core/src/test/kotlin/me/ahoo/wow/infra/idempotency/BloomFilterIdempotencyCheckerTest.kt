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
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class BloomFilterIdempotencyCheckerTest {

    @Test
    fun `should allow first element and reject duplicate element in cached filter`() {
        val creations = AtomicInteger()
        val checker = BloomFilterIdempotencyChecker(Duration.ofMinutes(1)) {
            creations.incrementAndGet()
            BloomFilter.create(Funnels.stringFunnel(Charsets.UTF_8), 100)
        }

        checker.check("request-1").assert().isTrue()
        checker.check("request-1").assert().isFalse()
        creations.get().assert().isEqualTo(1)
    }

    @Test
    fun `should refresh cached filter after ttl expires`() {
        val creations = AtomicInteger()
        val checker = BloomFilterIdempotencyChecker(Duration.ZERO) {
            creations.incrementAndGet()
            BloomFilter.create(Funnels.stringFunnel(Charsets.UTF_8), 100)
        }

        checker.check("request-1").assert().isTrue()
        checker.check("request-1").assert().isTrue()
        creations.get().assert().isGreaterThan(1)
    }

    @Test
    fun `should treat negative ttl as expired cache`() {
        val creations = AtomicInteger()
        val checker = BloomFilterIdempotencyChecker(Duration.ofNanos(-1)) {
            creations.incrementAndGet()
            BloomFilter.create(Funnels.stringFunnel(Charsets.UTF_8), 100)
        }

        checker.check("request-1").assert().isTrue()
        checker.check("request-1").assert().isTrue()
        creations.get().assert().isGreaterThan(1)
    }
}
