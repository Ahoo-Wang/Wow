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
 * The one assembly of a service's Manifest from its modules' fragments, the build's (`mergeManifest`) and the node's at
 * startup alike, so the Manifest a service serves is the one its build wrote: the fragments merged ([ManifestMerger]),
 * naming the service, then a subscription of another context's events by type given the aggregates the peer
 * contracts list them under ([PeerSources]).
 */
@InternalWowApi
object ManifestAssembly {
    /** The Manifest of [service], assembled from [fragments] with the peer [contracts]. */
    fun assemble(fragments: List<ManifestFragment>, service: ServiceInfo, contracts: List<Manifest>): Manifest =
        PeerSources.resolve(ManifestMerger.merge(fragments, service), contracts)
}
