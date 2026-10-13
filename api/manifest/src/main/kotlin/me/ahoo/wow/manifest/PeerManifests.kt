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

/**
 * The peer Manifests of a service (decision D139): the merged `manifest.json` of each other service whose contexts it
 * reads from or sends commands to, brought in at build time by the Gradle plugin's `wowPeers` configuration.
 *
 * On the class path, each lives at [resource] (`META-INF/wow/peers/<service>.json`), and the [INDEX] beside them
 * lists their file names, one per line, so a reader finds them without listing a directory of a jar.
 *
 * The build and the runtime refuse the same [conflicts], so a context always names one service.
 */
object PeerManifests {
    /** Where the peer Manifests live on the class path. */
    const val DIRECTORY: String = "META-INF/wow/peers"

    /** The file names of the peer Manifests of [DIRECTORY], one per line. */
    const val INDEX: String = "$DIRECTORY/index"

    /** The file name of the peer Manifest of [service] in [DIRECTORY]. */
    fun fileName(service: String): String = "$service.json"

    /** The class path resource of the peer Manifest of [service]. */
    fun resource(service: String): String = "$DIRECTORY/${fileName(service)}"

    /**
     * The [peers] a node reads on its class path, as the node that serves the contexts [served] sees them (DX gap
     * G21): each peer without the contexts the node serves, and without the peers left with none. Services that share
     * one class path (a shared-JVM test, each node selecting its contexts with `wow.contexts.include`) each carry
     * their peers' Manifests, so a node finds copies of its own Manifest among them; the node's own class path decides
     * the contexts it serves, never such a copy. Identical copies of one peer, from several servers, count once.
     *
     * What is left still has its [conflicts] among the peers: two services that both claim one context.
     */
    fun outside(served: Collection<String>, peers: List<ManifestFragment>): List<ManifestFragment> {
        val own = served.toSet()
        return peers.mapNotNull { peer ->
            val contexts = peer.manifest.contexts.filterNot { it.name in own }
            when {
                contexts.size == peer.manifest.contexts.size -> peer
                contexts.isEmpty() -> null
                else -> peer.copy(manifest = peer.manifest.copy(contexts = contexts))
            }
        }.distinctBy { it.manifest }
    }

    /**
     * Every conflict between the [peers] and the service named [service] whose own contexts are [local]:
     *
     * - a peer is a service's merged Manifest, which names its service;
     * - a service is one peer only, and not the service itself;
     * - a context belongs to one service: two peers do not declare the same context, and no peer declares one of
     *   [local].
     */
    fun conflicts(service: String?, local: Collection<String>, peers: List<ManifestFragment>): List<String> =
        buildList {
            peers.filter { it.manifest.service == null }.forEach {
                add("peer ${it.source} is not a service's merged Manifest: it names no service")
            }
            val named = peers.filter { it.manifest.service != null }
            named.groupBy { it.manifest.service!!.name }.forEach { (name, sources) ->
                if (sources.size > 1) {
                    add("service $name is the peer of ${sources.joinToString(" and ") { it.source }}")
                }
                if (name == service) {
                    add("peer ${sources.first().source} is of service $name itself")
                }
            }
            val localContexts = local.toSet()
            named.flatMap { peer -> peer.manifest.contexts.map { it.name to peer } }
                .groupBy({ it.first }, { it.second })
                .forEach { (context, owners) ->
                    if (owners.size > 1) {
                        add(
                            "context $context is declared by the peers " +
                                owners.joinToString(" and ") { "${it.manifest.service!!.name} (${it.source})" },
                        )
                    }
                    if (context in localContexts) {
                        add(
                            "context $context is declared by service ${service ?: "(unnamed)"} and by its peer " +
                                owners.joinToString(" and ") { "${it.manifest.service!!.name} (${it.source})" },
                        )
                    }
                }
        }
}
