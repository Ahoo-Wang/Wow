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

package me.ahoo.wow.bi

import me.ahoo.wow.api.modeling.NamedAggregate
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.util.concurrent.RejectedExecutionException

/**
 * Generates a BI script end to end: prepares the desired layout, inspects the ClickHouse catalog with [inspector],
 * and renders the script.
 *
 * Preparation and rendering are CPU work and run on [scheduler], a bounded pool by default; when it is saturated the
 * request fails as [BiDeploymentInspectionException.Unavailable] instead of queueing without bound.
 */
class BiScriptService(
    private val inspector: BiDeploymentInspector = NoOpBiDeploymentInspector,
    private val scheduler: Scheduler = BI_SCRIPT_GENERATION_SCHEDULER,
) {
    /**
     * Generates the script for the aggregates [namedAggregates] returns. It is called on [scheduler], because
     * resolving aggregate metadata can scan the classpath and must not run on the caller's (event-loop) thread.
     */
    fun generate(
        options: BiScriptOptions,
        operation: BiScriptOperation = BiScriptOperation.Deploy,
        namedAggregates: () -> Set<NamedAggregate>,
    ): Mono<BiScriptResult> {
        return Mono.fromCallable {
            val generator = BiScriptGenerator(options)
            generator to generator.prepare(namedAggregates())
        }
            .subscribeOn(scheduler)
            .mapOverload()
            .flatMap { (generator, preparation) ->
                inspector.inspect(options, operation, preparation).flatMap { inspection ->
                    Mono.fromCallable { generator.generate(preparation, operation, inspection) }
                        .subscribeOn(scheduler)
                        .mapOverload()
                }
            }
    }

    private fun <T : Any> Mono<T>.mapOverload(): Mono<T> =
        onErrorMap(RejectedExecutionException::class.java) { error ->
            BiDeploymentInspectionException.Unavailable(
                message = "Wow BI script generation is overloaded",
                cause = error,
            )
        }
}

private const val BI_SCRIPT_GENERATION_THREADS: Int = 4
private const val BI_SCRIPT_GENERATION_QUEUE_SIZE: Int = 256
private const val BI_SCRIPT_GENERATION_TTL_SECONDS: Int = 60
private val BI_SCRIPT_GENERATION_SCHEDULER: Scheduler = Schedulers.newBoundedElastic(
    BI_SCRIPT_GENERATION_THREADS,
    BI_SCRIPT_GENERATION_QUEUE_SIZE,
    "wow-bi-script-generation",
    BI_SCRIPT_GENERATION_TTL_SECONDS,
    true,
)
