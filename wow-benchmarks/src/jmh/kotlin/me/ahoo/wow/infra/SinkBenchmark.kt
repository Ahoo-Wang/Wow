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

package me.ahoo.wow.infra

import me.ahoo.wow.infra.sink.ConcurrentManySink
import me.ahoo.wow.infra.sink.concurrent
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Threads
import org.openjdk.jmh.annotations.Warmup
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers

@Warmup(iterations = 1)
@Measurement(iterations = 2)
@Fork(value = 2)
@State(Scope.Benchmark)
@Threads(2)
open class SinkBenchmark {
    private lateinit var sink: Sinks.Many<String>
    private lateinit var concurrentManySink: ConcurrentManySink<String>
    private lateinit var emitScheduler: Scheduler

    @Setup
    fun setup() {
        sink = Sinks.many().unicast().onBackpressureBuffer()
        sink.asFlux().subscribe()
        emitScheduler = Schedulers.newSingle("emit-scheduler")
        concurrentManySink = Sinks.unsafe().many().unicast().onBackpressureBuffer<String>().concurrent()
    }

    @Benchmark
    fun directSinkEmit() {
        sink.tryEmitNext("test").orThrow()
    }

    @Benchmark
    fun monoFromRunnableSend() {
        Mono.fromRunnable<Void> {
            sink.tryEmitNext("test").orThrow()
        }.block()
    }

    @Benchmark
    fun withConcurrentManySink() {
        concurrentManySink.tryEmitNext("test").orThrow()
    }

    @Benchmark
    fun withConcurrentManySinkFromRunnableSend() {
        Mono.fromRunnable<Void> {
            concurrentManySink.tryEmitNext("test").orThrow()
        }.block()
    }

    @Benchmark
    fun monoFromRunnableSendWithSubscribeOnSingleScheduler() {
        Mono.fromRunnable<Void> {
            sink.tryEmitNext("test").orThrow()
        }.subscribeOn(emitScheduler).block()
    }

}