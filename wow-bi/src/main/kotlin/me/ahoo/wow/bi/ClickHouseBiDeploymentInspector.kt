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

import com.clickhouse.client.api.ClientException
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeoutException

private const val CLICKHOUSE_CATALOG_INSPECTION_THREADS: Int = 4
private const val CLICKHOUSE_CATALOG_INSPECTION_QUEUE_SIZE: Int = 256
private const val CLICKHOUSE_CATALOG_INSPECTION_TTL_SECONDS: Int = 60
private val CLICKHOUSE_CATALOG_INSPECTION_SCHEDULER: Scheduler = Schedulers.newBoundedElastic(
    CLICKHOUSE_CATALOG_INSPECTION_THREADS,
    CLICKHOUSE_CATALOG_INSPECTION_QUEUE_SIZE,
    "wow-bi-catalog-inspection",
    CLICKHOUSE_CATALOG_INSPECTION_TTL_SECONDS,
    true,
)

/**
 * Reads Wow BI ownership markers directly from the ClickHouse system catalog.
 *
 * This inspector owns its ClickHouse client. Call [close] when the inspector is no longer used.
 */
class ClickHouseBiDeploymentInspector internal constructor(
    private val catalogClient: ClickHouseCatalogClient,
    private val inspectionTimeout: Duration = DEFAULT_INSPECTION_TIMEOUT,
    private val timeoutScheduler: Scheduler = Schedulers.parallel(),
    private val inspectionScheduler: Scheduler = CLICKHOUSE_CATALOG_INSPECTION_SCHEDULER,
) : BiDeploymentInspector, AutoCloseable {
    private val catalogReader = ClickHouseCatalogReader(catalogClient)

    constructor(
        clientOptions: ClickHouseClientOptions,
        inspectionTimeout: Duration = DEFAULT_INSPECTION_TIMEOUT,
    ) : this(
        catalogClient = createCatalogClient(clientOptions, inspectionTimeout),
        inspectionTimeout = inspectionTimeout,
    )

    init {
        inspectionTimeout.requireValidTimeout("inspectionTimeout")
    }

    override fun inspect(
        options: BiScriptOptions,
        operation: BiScriptOperation,
        preparation: BiScriptPreparation,
    ): Mono<BiDeploymentInspection> {
        require(preparation.options == options) {
            "BI inspection preparation belongs to different script options"
        }
        return Mono.just(preparation)
            .flatMap {
                inspectCatalog(
                    options = options,
                    operation = operation,
                    preparation = it,
                )
            }
            .onErrorMap(RejectedExecutionException::class.java, ::inspectionOverloaded)
    }

    private fun inspectCatalog(
        options: BiScriptOptions,
        operation: BiScriptOperation,
        preparation: BiScriptPreparation,
    ): Mono<BiDeploymentInspection> = Mono.defer {
        val cancellation = ClickHouseQueryCancellation()
        Mono.fromCallable<BiDeploymentInspection> {
            inspectCatalogBlocking(options, operation, preparation, cancellation)
        }
            .subscribeOn(inspectionScheduler)
            .doOnCancel(cancellation::cancel)
            .timeout(inspectionTimeout, timeoutScheduler)
    }
        .onErrorMap(TimeoutException::class.java) { error ->
            BiDeploymentInspectionException.Timeout(
                message = "ClickHouse BI deployment inspection timed out",
                cause = error,
            )
        }
        .onErrorMap(RejectedExecutionException::class.java) { error ->
            inspectionOverloaded(error)
        }

    private fun inspectCatalogBlocking(
        options: BiScriptOptions,
        operation: BiScriptOperation,
        preparation: BiScriptPreparation,
        cancellation: ClickHouseQueryCancellation,
    ): BiDeploymentInspection {
        try {
            val snapshot = catalogReader.read(
                ClickHouseCatalogReadRequest(
                    options = options,
                    operation = operation,
                    desiredObjectKeys = preparation.desiredObjectKeys,
                    desiredObjects = preparation.desiredObjects,
                    cancellation = cancellation,
                )
            )
            val validated = ClickHouseBiDeploymentValidator.validate(
                options,
                operation,
                snapshot,
                preparation.desiredObjects,
            )
            return BiDeploymentInspection.Available.reconciled(
                deployment = validated.deployment,
                repairableComputedDrifts = validated.repairableDrifts,
                ownershipRegistry = snapshot.ownershipRegistry,
            )
        } catch (error: BiDeploymentInspectionException) {
            return error.cancelledInspectionOrThrow(cancellation)
        } catch (error: IllegalArgumentException) {
            return BiDeploymentInspectionException.Inconsistent(
                error.message ?: "ClickHouse BI catalog is inconsistent",
                error,
            ).cancelledInspectionOrThrow(cancellation)
        } catch (error: IllegalStateException) {
            return BiDeploymentInspectionException.Inconsistent(
                error.message ?: "ClickHouse BI catalog is inconsistent",
                error,
            ).cancelledInspectionOrThrow(cancellation)
        } catch (error: ClientException) {
            return error.toInspectionException().cancelledInspectionOrThrow(cancellation)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            return error.cancelledInspectionOrThrow(cancellation)
        } finally {
            if (cancellation.isCancelled) {
                Thread.interrupted()
            }
        }
    }

    override fun close() {
        catalogClient.close()
    }

    private companion object {
        val DEFAULT_INSPECTION_TIMEOUT: Duration = Duration.ofSeconds(30)

        fun createCatalogClient(
            options: ClickHouseClientOptions,
            inspectionTimeout: Duration,
        ): NativeClickHouseCatalogClient {
            inspectionTimeout.requireValidTimeout("inspectionTimeout")
            require(options.socketTimeout <= inspectionTimeout) {
                "socketTimeout [${options.socketTimeout}] must not exceed inspectionTimeout [$inspectionTimeout]"
            }
            return NativeClickHouseCatalogClient.create(options)
        }
    }
}

private fun Throwable.cancelledInspectionOrThrow(
    cancellation: ClickHouseQueryCancellation,
): BiDeploymentInspection {
    if (cancellation.isCancelled) {
        return BiDeploymentInspection.Unavailable
    }
    throw this
}

private fun inspectionOverloaded(error: RejectedExecutionException): BiDeploymentInspectionException.Unavailable =
    BiDeploymentInspectionException.Unavailable(
        message = "ClickHouse BI catalog inspection is overloaded",
        cause = error,
    )

private fun ClientException.toInspectionException(): BiDeploymentInspectionException {
    if (generateSequence<Throwable>(this) { error -> error.cause }.any(Throwable::isTimeout)) {
        return BiDeploymentInspectionException.Timeout(
            message = "ClickHouse BI deployment inspection timed out",
            cause = this,
        )
    }
    return BiDeploymentInspectionException.Unavailable(
        message = "ClickHouse BI deployment inspection is unavailable",
        cause = this,
    )
}

private fun Throwable.isTimeout(): Boolean =
    this is TimeoutException || javaClass.simpleName.endsWith("TimeoutException")
