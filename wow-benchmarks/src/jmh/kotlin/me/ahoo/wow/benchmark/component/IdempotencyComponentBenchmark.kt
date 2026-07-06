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

package me.ahoo.wow.benchmark.component

import me.ahoo.wow.benchmark.fixture.BenchmarkIdempotency
import me.ahoo.wow.benchmark.fixture.BenchmarkIds
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.infra.idempotency.BloomFilterIdempotencyChecker
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Thread)
open class IdempotencyComponentBenchmark {
    private companion object {
        const val KNOWN_REQUEST_ID = "known-request-id"
    }

    private lateinit var idempotencyChecker: BloomFilterIdempotencyChecker
    private lateinit var bloomFilterChecker: BloomFilterIdempotencyChecker

    @Setup
    fun setup() {
        BenchmarkIds.installDeterministicGlobalIdGenerator()
        idempotencyChecker = BenchmarkIdempotency.bloomFilterChecker()
        bloomFilterChecker = BenchmarkIdempotency.bloomFilterChecker()
        idempotencyChecker.check(KNOWN_REQUEST_ID)
    }

    @Benchmark
    fun checkNewRequestId(blackhole: Blackhole) {
        val result = idempotencyChecker.check(generateGlobalId())
        blackhole.consume(result)
    }

    @Benchmark
    fun checkKnownRequestId(blackhole: Blackhole) {
        val result = idempotencyChecker.check(KNOWN_REQUEST_ID)
        blackhole.consume(result)
    }

    @Benchmark
    fun checkBloomFilterRequestId(blackhole: Blackhole) {
        val result = bloomFilterChecker.check(BenchmarkIds.nextGlobalId())
        blackhole.consume(result)
    }
}
