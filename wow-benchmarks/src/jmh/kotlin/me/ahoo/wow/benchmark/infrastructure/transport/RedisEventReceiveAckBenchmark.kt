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

package me.ahoo.wow.benchmark.infrastructure.transport

import me.ahoo.wow.infrastructure.redis.RedisBenchmarkFixture
import me.ahoo.wow.redis.bus.RedisDomainEventBus
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole

/**
 * Redis Streams event bus: send → `XREADGROUP` receive → `XACK`, one event stream per operation, through one
 * receiver. Needs the benchmark Redis (`docker/compose.redis.yml`).
 *
 * Audit 9.3.0 B §F5 / §F7 (design WP G2; X7 compares against this).
 */
@State(Scope.Benchmark)
open class RedisEventReceiveAckBenchmark {
    private lateinit var fixture: RedisBenchmarkFixture
    internal lateinit var harness: TransportReceiveAckHarness

    @Setup(Level.Trial)
    fun setup() {
        fixture = RedisBenchmarkFixture()
        harness = TransportReceiveAckHarness(RedisDomainEventBus(fixture.redisTemplate), "benchmark-receive-ack")
        check(harness.roundTrip(harness.newProducer().next())) { "Redis probe round trip timed out." }
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        try {
            harness.close()
        } finally {
            fixture.close()
        }
    }

    @Benchmark
    fun sendReceiveAck(producer: Producer, blackhole: Blackhole) {
        check(harness.roundTrip(producer.next())) { "Redis receive/ack round trip timed out." }
        blackhole.consume(producer)
    }

    @State(Scope.Thread)
    open class Producer {
        private lateinit var delegate: TransportReceiveAckHarness.Producer

        @Setup(Level.Trial)
        fun setup(benchmark: RedisEventReceiveAckBenchmark) {
            delegate = benchmark.harness.newProducer()
        }

        fun next() = delegate.next()
    }
}
