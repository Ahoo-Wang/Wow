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

package me.ahoo.wow.spring.boot

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.spi.config.configCheck

/**
 * The bounded contexts a node serves (decision D169): `wow.contexts.include` and `wow.contexts.exclude`
 * ([WowProperties.Contexts]) over the domain modules on the class path, their binding indexes ([ModelProvider]).
 *
 * - Neither set: every context on the class path, as before.
 * - [include] set: only those contexts; then [exclude] removes contexts from what is left.
 * - Each name must be a context of a module on the class path, and none both included and excluded; a selection that
 *   leaves no context is refused too. The node then fails to start, naming the contexts on the class path.
 *
 * The node is built for the selected contexts only: their models, their Manifest fragments (so the service's
 * Manifest, its routes and its contract name them alone), and the handlers of their processors. A context left out
 * is to the node as any other service's, reached through its peers.
 */
@InternalWowApi
data class ContextSelection(val include: Set<String> = emptySet(), val exclude: Set<String> = emptySet()) {
    /** Whether the selection serves every context on the class path. */
    val isAll: Boolean get() = include.isEmpty() && exclude.isEmpty()

    /** The providers of [providers] whose context is selected; fails on a selection that names an unknown context. */
    fun select(providers: List<ModelProvider>): List<ModelProvider> {
        if (isAll) {
            return providers
        }
        val available = providers.mapTo(sortedSetOf()) { it.context }
        val unknown = (include + exclude).filterNot { it in available }.sorted()
        configCheck(
            unknown.isEmpty(),
            "wow.contexts",
            unknown.joinToString(),
            "Name only contexts on the class path: $available."
        ) {
            "wow.contexts names ${unknown.joinToString()}, which no domain module on the class path declares."
        }
        val both = include.intersect(exclude).sorted()
        configCheck(
            both.isEmpty(),
            "wow.contexts",
            both.joinToString(),
            "Remove each from the include or the exclude."
        ) {
            "wow.contexts both includes and excludes ${both.joinToString()}."
        }
        val selected = providers.filter { (include.isEmpty() || it.context in include) && it.context !in exclude }
        configCheck(
            selected.isNotEmpty(),
            "wow.contexts",
            "include $include, exclude $exclude",
            "Select one of $available."
        ) {
            "wow.contexts selects no context: the include, less the exclude, leaves none of the contexts."
        }
        return selected
    }
}
