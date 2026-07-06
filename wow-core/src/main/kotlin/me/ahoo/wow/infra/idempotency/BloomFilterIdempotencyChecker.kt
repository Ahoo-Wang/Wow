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
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration

/**
 * Idempotency checker implementation using Bloom filters for efficient duplicate detection.
 * Bloom filters provide probabilistic duplicate detection with configurable false positive rates,
 * making them suitable for high-throughput scenarios where perfect accuracy is not required.
 *
 * The checker automatically refreshes the Bloom filter based on the configured TTL,
 * ensuring that old entries are periodically cleared to prevent memory leaks.
 *
 * Note: Bloom filters can produce false positives (reporting an element as duplicate when it's not),
 * but never false negatives (missing actual duplicates).
 *
 * @param ttl the time-to-live duration for the Bloom filter before it gets refreshed
 * @param bloomFilterSupplier supplier function that creates new Bloom filter instances
 * @author ahoo wang
 * @see <a href="http://llimllib.github.io/bloomfilter-tutorial/">Bloom Filter Tutorial</a>
 */
@Suppress("UnstableApiUsage")
class BloomFilterIdempotencyChecker(
    private val ttl: Duration,
    private val bloomFilterSupplier: () -> BloomFilter<String>
) : IdempotencyChecker {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val ttlNanos = ttl.coerceAtLeast(Duration.ZERO).toNanos()
    private val refreshLock = Any()

    @Volatile
    private var bloomFilter: BloomFilter<String>? = null

    @Volatile
    private var expiresAt: Long = 0

    private fun currentBloomFilter(): BloomFilter<String> {
        val now = System.nanoTime()
        val current = bloomFilter
        if (current != null && isNotExpired(now)) {
            return current
        }
        return synchronized(refreshLock) {
            val lockedNow = System.nanoTime()
            val lockedCurrent = bloomFilter
            if (lockedCurrent != null && isNotExpired(lockedNow)) {
                lockedCurrent
            } else {
                newBloomFilter(lockedNow)
            }
        }
    }

    private fun isNotExpired(now: Long): Boolean {
        return now - expiresAt < 0
    }

    private fun newBloomFilter(now: Long): BloomFilter<String> {
        log.info {
            "Create new BloomFilter."
        }
        return bloomFilterSupplier().also {
            bloomFilter = it
            expiresAt = now + ttlNanos
        }
    }

    /**
     * Checks if the element is a potential duplicate using the Bloom filter.
     * If the element might be contained (possible duplicate), returns false.
     * If the element is definitely not contained (unique), adds it to the filter and returns true.
     *
     * @param element the element to check for duplicates
     * @return true if the element appears to be unique, false if it's a potential duplicate
     */
    override fun check(element: String): Boolean {
        val currentBloomFilter = currentBloomFilter()
        val contain = currentBloomFilter.mightContain(element)
        if (!contain) {
            currentBloomFilter.put(element)
        }
        return !contain
    }
}
