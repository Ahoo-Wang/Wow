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
import com.google.common.hash.Funnel
import com.google.common.hash.PrimitiveSink
import reactor.core.publisher.Mono

/**
 * BloomFilterIdempotencyChecker .
 * [bloomfilter-tutorial](http://llimllib.github.io/bloomfilter-tutorial/)
 *
 * @author ahoo wang
 */
@Suppress("UnstableApiUsage")
class BloomFilterIdempotencyChecker(private val bloomFilter: BloomFilter<String>) : IdempotencyChecker {
    constructor(expectedInsertions: Long, fpp: Double) : this(
        BloomFilter.create<String>(
            STRING_FUNNEL,
            expectedInsertions,
            fpp,
        ),
    )

    override fun check(element: String): Mono<Boolean> {
        return Mono.fromCallable {
            val contain = bloomFilter.mightContain(element)
            if (!contain) {
                bloomFilter.put(element)
                return@fromCallable true
            }
            false
        }
    }

    companion object {
        val STRING_FUNNEL = Funnel { from: String, into: PrimitiveSink -> into.putString(from, Charsets.UTF_8) }
    }
}
