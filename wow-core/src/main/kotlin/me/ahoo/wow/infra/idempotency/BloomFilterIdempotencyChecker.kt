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
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * BloomFilterIdempotencyChecker .
 * [bloomfilter-tutorial](http://llimllib.github.io/bloomfilter-tutorial/)
 *
 * @author ahoo wang
 */
@Suppress("UnstableApiUsage")
class BloomFilterIdempotencyChecker(
    private val ttl: Duration,
    private val bloomFilterSupplier: () -> BloomFilter<String>
) :
    IdempotencyChecker {
    companion object {
        private val log = LoggerFactory.getLogger(BloomFilterIdempotencyChecker::class.java)
    }

    private val bloomFilterCache = Mono.fromCallable {
        if (log.isInfoEnabled) {
            log.info("Create new BloomFilter.")
        }
        bloomFilterSupplier()
    }.cache(ttl)

    override fun check(element: String): Mono<Boolean> {
        return bloomFilterCache.map {
            val contain = it.mightContain(element)
            if (!contain) {
                it.put(element)
            }
            !contain
        }
    }
}
