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

package me.ahoo.wow.viewstore.starter.system

import me.ahoo.wow.viewstore.api.SystemView
import reactor.core.publisher.Flux

/**
 * The system views stored under the tenant `(platform)` and the owner `(system)`, global to every tenant. The default reads
 * the view snapshots through the host's snapshot query backend; a host without one (in-memory snapshots) gets [NONE]
 * and a warning at startup. A host's own bean replaces either.
 */
fun interface StoredSystemViewSource {
    companion object {
        /** No stored system views: the configured ones are served alone. */
        val NONE: StoredSystemViewSource = StoredSystemViewSource { _, _, _ -> Flux.empty() }
    }

    /**
     * The stored system views of [appId], not deleted, oldest first: of [definitionId] only when it is given, the
     * one with [id] only when it is given. Each is a [SystemView] of source `stored` with its version.
     */
    fun storedSystemViews(appId: String, definitionId: String?, id: String?): Flux<SystemView>
}
