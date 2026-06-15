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

package me.ahoo.wow.benchmark.webflux

import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import me.ahoo.wow.webflux.route.toEventStreamResponse
import me.ahoo.wow.webflux.route.command.toCommandResponse
import me.ahoo.wow.webflux.route.toServerResponse
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import org.springframework.http.codec.ServerSentEvent
import reactor.core.publisher.Flux
import reactor.kotlin.core.publisher.toMono

@State(Scope.Thread)
open class WebFluxResponseBenchmark {
    private val jsonRequest = WebFluxBenchmarkSupport.jsonRequest()
    private val sseRequest = WebFluxBenchmarkSupport.sseRequest()
    private val payloads = WebFluxBenchmarkSupport.responsePayloads()
    private val commandResults = List(payloads.size) {
        WebFluxBenchmarkSupport.commandResult()
    }
    private val serverSentEvents = commandResults.map {
        it.toServerSentEvent()
    }
    private val preparedSseResponse = Flux.fromIterable(serverSentEvents)
        .toEventStreamResponse(sseRequest, DefaultRequestExceptionHandler)
        .block()!!

    @Benchmark
    fun monoCommandResultResponse(blackhole: Blackhole) {
        val response = WebFluxBenchmarkSupport.commandResult()
            .toMono()
            .toServerResponse(jsonRequest, DefaultRequestExceptionHandler)
            .block()
        blackhole.consume(response)
    }

    @Benchmark
    fun fluxJsonCollectListResponse(blackhole: Blackhole) {
        val response = Flux.fromIterable(payloads)
            .toServerResponse(jsonRequest, DefaultRequestExceptionHandler)
            .block()
        blackhole.consume(response)
    }

    @Benchmark
    fun commandResultSseEventMapping(blackhole: Blackhole) {
        val events = Flux.fromIterable(commandResults)
            .map {
                it.toServerSentEvent()
            }
            .collectList()
            .block()
        blackhole.consume(events)
    }

    @Benchmark
    fun commandResultSseServerResponseOnly(blackhole: Blackhole) {
        val response = Flux.fromIterable(serverSentEvents)
            .toEventStreamResponse(sseRequest, DefaultRequestExceptionHandler)
            .block()
        blackhole.consume(response)
    }

    @Benchmark
    fun commandResultSseWriteToExchange(blackhole: Blackhole) {
        preparedSseResponse
            .writeTo(WebFluxBenchmarkSupport.sseExchange(), WebFluxBenchmarkSupport.sseResponseContext)
            .block()
        blackhole.consume(preparedSseResponse)
    }

    @Benchmark
    fun commandResultSseResponse(blackhole: Blackhole) {
        val response = Flux.range(1, payloads.size)
            .map { WebFluxBenchmarkSupport.commandResult() }
            .toCommandResponse(sseRequest, DefaultRequestExceptionHandler)
            .block()
        response
            ?.writeTo(WebFluxBenchmarkSupport.sseExchange(), WebFluxBenchmarkSupport.sseResponseContext)
            ?.block()
        blackhole.consume(response)
    }

    private fun CommandResult.toServerSentEvent(): ServerSentEvent<String> {
        return ServerSentEvent.builder<String>()
            .id(id)
            .event(stage.name)
            .data(toJsonString())
            .build()
    }
}
