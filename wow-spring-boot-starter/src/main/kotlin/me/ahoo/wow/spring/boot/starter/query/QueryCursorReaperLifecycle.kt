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

package me.ahoo.wow.spring.boot.starter.query

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.spring.WOW_RUNTIME_PHASE
import org.springframework.context.SmartLifecycle
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers

/** Owns the single opt-in Cursor reaper schedule and never overlaps reaping runs. */
internal class QueryCursorReaperLifecycle(
    private val reap: (Int) -> Mono<Long>,
    private val properties: QueryCursorReaperProperties,
    private val scheduler: Scheduler = Schedulers.newSingle("wow-query-cursor-reaper", true),
    private val ownsScheduler: Boolean = true,
) : SmartLifecycle {
    private enum class State {
        NEW,
        RUNNING,
        STOPPED,
    }

    private val monitor = Any()
    private var subscription: Disposable? = null

    @Volatile
    private var state = State.NEW

    override fun start() {
        synchronized(monitor) {
            when (state) {
                State.RUNNING -> return
                State.STOPPED -> error("Query cursor reaper cannot restart after it has stopped.")
                State.NEW -> state = State.RUNNING
            }
            subscription = Flux.interval(properties.initialDelay, properties.interval, scheduler)
                .onBackpressureDrop {
                    log.warn { "Skipping one Query cursor reaper tick because the previous run is still active." }
                }.concatMap(
                    {
                        reapRun()
                            .doOnNext { reaped ->
                                if (reaped > 0) {
                                    log.info { "Reaped $reaped expired Query cursor lease(s)." }
                                }
                            }.onErrorResume { error ->
                                log.error(error) { "Query cursor lease reaping failed; the next scheduled run remains active." }
                                Mono.empty()
                            }
                    },
                    1,
                ).subscribe(
                    {},
                    { error -> log.error(error) { "Query cursor reaper schedule terminated unexpectedly." } },
                )
        }
    }

    override fun stop() {
        val current = synchronized(monitor) {
            if (state == State.STOPPED) return
            state = State.STOPPED
            subscription.also { subscription = null }
        }
        current?.dispose()
        if (ownsScheduler) scheduler.dispose()
    }

    override fun isRunning(): Boolean = state == State.RUNNING

    override fun getPhase(): Int = WOW_RUNTIME_PHASE + 1

    private fun reapRun(): Mono<Long> = Flux.range(0, properties.maxBatchesPerRun)
        .concatMap(
            {
                Mono.defer { reap(properties.batchSize) }
                    .switchIfEmpty(Mono.error(IllegalStateException("Query cursor reaper returned no result.")))
                    .map(::validateBatchResult)
            },
            1,
        ).takeUntil { reaped -> reaped < properties.batchSize }
        .reduce(0L, Math::addExact)

    private fun validateBatchResult(reaped: Long): Long {
        check(reaped in 0..properties.batchSize.toLong()) {
            "Query cursor reaper returned $reaped for batch size ${properties.batchSize}."
        }
        return reaped
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
