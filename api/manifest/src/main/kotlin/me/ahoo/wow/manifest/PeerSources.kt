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
 * the schemas the handler takes, all of one context, each read once: several events of one aggregate (the subtypes of a
 * sealed event type) read that aggregate. Each schema names an event of exactly one aggregate; an event two aggregates
 * list, one no contract lists, or events of several contexts are a problem, which names the candidates.
 */
@InternalWowApi
object PeerSources {
    /**
     * [manifest] with every subscription that takes another context's events by type reading the aggregates the
     * [contracts] list them under; a [ContractException] listing every subscription it cannot say. An event no contract
     * lists is named with the class path entry [locate] finds its class in, when it finds one: the api module there
     * carries no contract that lists it, a types-only release of a cycle's first member, or one of no `wowContract`.
     */
    @JvmStatic
    fun resolve(manifest: Manifest, contracts: List<Manifest>, locate: (String) -> String? = { null }): Manifest {
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
                resolved(context.name, subscription, owners, problems, locate)
            }
            context.copyWith(subscriptions = subscriptions)
        }
        if (problems.isNotEmpty()) {
            throw ContractException(problems)
        }
        return manifest.copyWith(contexts = contexts)
    }

    /** What to do about the event class [binary] no contract lists, as [locate] finds it on the class path. */
    private fun unlisted(binary: String, locate: (String) -> String?): String {
        val entry = locate(binary) ?: return "depend on the api module of the context that publishes it"
        return "it is in $entry, whose api module carries no contract listing it: a types-only release " +
            "(-P$PUBLISH_PROPERTY=false), the first of a cycle of contexts consuming each other, or an api module " +
            "that declares no wowContract. Depend on the module's full release, whose contract lists the event"
    }

    /** The Gradle property that publishes an api module's types alone, as its message names it. */
    private const val PUBLISH_PROPERTY = "wow.contract.publish"

    private fun resolved(
        context: String,
        subscription: SubscriptionManifest,
        owners: Map<String, List<String>>,
        problems: MutableList<String>,
        locate: (String) -> String?,
    ): SubscriptionManifest {
        val source = subscription.source
        if (source.eventSchemas.isEmpty()) return subscription
        val where = "subscription $context/${subscription.name}"
        val aggregates = source.eventSchemas.map { schema ->
            val candidates = owners[schema].orEmpty()
            when (candidates.size) {
                0 -> problems += "$where takes ${Manifest.schemaKey(schema)}, which no peer contract on the class " +
                    "path lists as an event: " + unlisted(Manifest.schemaKey(schema), locate)

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
        // A schema without its one owner is a problem recorded above; several schemas of one owner are not (#6840).
        if (aggregates.any { it == null } || peerContexts.size != 1) return subscription
        // The event schemas are resolved into the context and aggregates that publish them.
        return subscription.copyWith(
            source = source.copyWith(
                context = peerContexts.single(),
                aggregates = found.map { it.substringAfter('.') },
                eventSchemas = emptyList(),
            ),
        )
    }
}
