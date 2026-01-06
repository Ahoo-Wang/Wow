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

package me.ahoo.wow.command

import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.infra.idempotency.IdempotencyChecker
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Threads
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole

@Warmup(iterations = 1)
@Measurement(iterations = 2)
@Fork(value = 2)
@Threads(2)
@State(Scope.Benchmark)
open class BloomFilterIdempotencyCheckerBenchmark {
    private lateinit var idempotencyChecker: IdempotencyChecker

    @Setup
    fun setup() {
        idempotencyChecker = createBloomFilterIdempotencyChecker()
    }

    @Benchmark
    fun check(blackhole: Blackhole) {
        val result = idempotencyChecker.check(generateGlobalId()).block()
        blackhole.consume(result)
    }
}