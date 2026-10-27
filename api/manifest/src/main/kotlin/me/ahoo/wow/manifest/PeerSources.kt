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

package me.ahoo.wow.manifest

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * The aggregates a subscription of another context's events reads when its handler takes them by type
 * without naming their aggregate ([SubscriptionSource.eventSchemas]): those whose events the peer contracts list under
 * the schemas the handler takes, all of one context. Each schema names an event of exactly one aggregate; an event two
 * aggregates list, one no contract lists, or events of several contexts are a problem, which names the candidates.
 */
@InternalWowApi
object PeerSources {
    /**
     * [manifest] with every subscription that takes another context's events by type reading the aggregates the
     * [contracts] list them under; a [ContractException] listing every subscription it cannot say.
     */
    @JvmStatic
    fun resolve(manifest: Manifest, contracts: List<Manifest>): Manifest {
        if (manifest.contexts.none { context -> context.subscriptions.any { it.source.eventSchemas.isNotEmpty() } }) {
            return manifest
        }
        val owners = contracts.flatMap { it.contexts }.flatMap { context ->
            context.aggregates.flatMap { aggregate ->
                aggregate.events.map { it.schema to "${context.name}.${aggregate.name}" }
            }
        }.groupBy({ it.first }, { it.second }).mapValues { (_, aggregates) -> aggregates.distinct() }
        val problems = mutableListOf<String>()
        val contexts = manifest.contexts.map { context ->
            val subscriptions = context.subscriptions.map { subscription ->
                resolved(context.name, subscription, owners, problems)
            }
            ContextManifest(
                name = context.name,
                description = context.description,
                aggregates = context.aggregates,
                subscriptions = subscriptions,
                published = context.published,
                errors = context.errors,
            )
        }
        if (problems.isNotEmpty()) {
            throw ContractException(problems)
        }
        return Manifest(manifest.format, manifest.service, contexts, manifest.errors, manifest.defs)
    }

    private fun resolved(
        context: String,
        subscription: SubscriptionManifest,
        owners: Map<String, List<String>>,
        problems: MutableList<String>,
    ): SubscriptionManifest {
        val source = subscription.source
        if (source.eventSchemas.isEmpty()) return subscription
        val where = "subscription $context/${subscription.name}"
        val aggregates = source.eventSchemas.map { schema ->
            val candidates = owners[schema].orEmpty()
            when (candidates.size) {
                0 -> problems += "$where takes ${Manifest.schemaKey(schema)}, which no peer contract on the class " +
                    "path lists as an event: depend on the api module of the context that publishes it"

                1 -> Unit
                else -> problems += "$where takes ${Manifest.schemaKey(schema)}, an event of " +
                    "${candidates.sorted().joinToString()}: name one with @OnEvent(aggregates = [...QUALIFIED_NAME])"
            }
            candidates.singleOrNull()
        }
        val found = aggregates.filterNotNull().distinct().sorted()
        val peerContexts = found.map { it.substringBefore('.') }.distinct()
        if (peerContexts.size > 1) {
            problems += "$where takes events of the contexts ${peerContexts.joinToString()}: a handler reads one context"
        }
        if (found.size != aggregates.size || peerContexts.size != 1) return subscription
        return SubscriptionManifest(
            name = subscription.name,
            kind = subscription.kind,
            source = SubscriptionSource(
                context = peerContexts.single(),
                aggregates = found.map { it.substringAfter('.') },
                events = source.events,
                nullableState = source.nullableState,
            ),
            function = subscription.function,
            derives = subscription.derives,
            rejected = subscription.rejected,
            start = subscription.start,
            schedules = subscription.schedules,
            retry = subscription.retry,
            committed = subscription.committed,
            job = subscription.job,
            calls = subscription.calls,
        )
    }
}
