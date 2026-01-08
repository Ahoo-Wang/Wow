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

package me.ahoo.wow.reactor

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.infra.sink.concurrent
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Schedulers
import java.time.Duration

class LifecycleTest {
    companion object {
        private val log = KotlinLogging.logger { }
    }

    @Test
    fun tryEmitComplete() {
        val sink = Sinks.unsafe().many().unicast().onBackpressureBuffer<String>().concurrent()
        sink.asFlux().publishOn(Schedulers.boundedElastic()).flatMap { record ->
            Mono.delay(Duration.ofMillis(100)).map {
                log.info { "flatMap: $record" }
            }
        }.doOnComplete {
            log.info { "doOnComplete" }
        }.subscribe()
        sink.tryEmitNext("1").orThrow()
        sink.tryEmitNext("2").orThrow()
        sink.tryEmitComplete().orThrow()
        assertThrownBy<Sinks.EmissionException> { sink.tryEmitNext("3").orThrow() }
        Thread.sleep(1000)
    }

    @Test
    fun cancel() {
        val sink = Sinks.unsafe().many().unicast().onBackpressureBuffer<String>().concurrent()
        val subscription = sink.asFlux().publishOn(Schedulers.boundedElastic()).flatMap { record ->
            Mono.delay(Duration.ofMillis(100)).map {
                log.info { "flatMap: $record" }
            }
        }.doOnComplete {
            log.info { "doOnComplete" }
        }.subscribe()
        sink.tryEmitNext("1").orThrow()
        sink.tryEmitNext("2").orThrow()

        subscription.dispose()
        assertThrownBy<Sinks.EmissionException> { sink.tryEmitNext("3").orThrow() }
        Thread.sleep(1000)
    }
}
