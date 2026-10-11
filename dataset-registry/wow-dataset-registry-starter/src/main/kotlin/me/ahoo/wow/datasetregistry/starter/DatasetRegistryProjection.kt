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

package me.ahoo.wow.datasetregistry.starter

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.schema.QueryDataset
import me.ahoo.wow.query.schema.QueryDatasets
import org.springframework.context.SmartLifecycle
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves the registry's published datasets on this instance (dataset platform design §4.3): it reads them now and
 * every [interval], and adds, replaces or removes them in [datasets]. Reading the published versions, rather than
 * following the registry's events, lets every instance converge on them whichever one handled a command.
 *
 * A dataset of a declaration file wins: a published dataset of the same name is not served (the registry refuses to
 * publish one, [CompilingDatasetPublicationCheck]). A published version this instance cannot serve (its table changed,
 * say) is logged and left out; the other datasets are served.
 */
class DatasetRegistryProjection(
    private val datasets: QueryDatasets,
    private val source: PublishedDatasetSource,
    private val interval: Duration,
) : SmartLifecycle {
    private val served = ConcurrentHashMap<MaterializedNamedAggregate, Int>()

    @Volatile
    private var subscription: Disposable? = null

    /** The datasets this projection serves: the registry's, not the declaration files'. */
    val names: Set<MaterializedNamedAggregate>
        get() = served.keys

    /** Serves the currently published versions once. */
    fun refresh(): Mono<Void> = source.published().collectList().doOnNext(::apply).then()

    private fun apply(published: List<PublishedDataset>) {
        val current = published.mapNotNull { dataset ->
            runCatching { QueryDataset.nameOf(dataset.name) to dataset }
                .onFailure { log.warn { "Published dataset [${dataset.name}] has an invalid name: ${it.message}" } }
                .getOrNull()
        }.toMap()
        (served.keys - current.keys).forEach { name ->
            datasets.remove(name)
            served.remove(name)
        }
        current.forEach { (name, dataset) -> serve(name, dataset) }
    }

    private fun serve(name: MaterializedNamedAggregate, published: PublishedDataset) {
        if (served[name] == published.version) return
        runCatching {
            val dataset = QueryDataset.parse(name, published.document.toString())
            when {
                served.containsKey(name) -> datasets.replace(dataset)
                datasets[name] == null -> datasets.add(dataset)
                else -> {
                    log.warn { "Dataset [$name] is declared by a file, which wins over its published version." }
                    return
                }
            }
            served[name] = published.version
        }.onFailure { log.warn(it) { "Unable to serve version [${published.version}] of dataset [$name]." } }
    }

    override fun start() {
        subscription = Flux.interval(Duration.ZERO, interval)
            .onBackpressureDrop()
            .concatMap({ refreshQuietly() }, 1)
            .subscribe()
    }

    private fun refreshQuietly(): Mono<Void> = refresh().onErrorResume {
        log.warn(it) { "Unable to read the published datasets." }
        Mono.empty()
    }

    override fun stop() {
        subscription?.dispose()
        subscription = null
    }

    override fun isRunning(): Boolean = subscription?.isDisposed == false

    private companion object {
        val log = KotlinLogging.logger { }
    }
}
