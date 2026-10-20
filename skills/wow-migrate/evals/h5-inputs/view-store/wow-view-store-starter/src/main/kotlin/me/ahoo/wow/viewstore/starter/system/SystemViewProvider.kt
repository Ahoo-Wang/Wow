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
 * The configured, read-only views the server offers a tenant's application, for every definition. The view store
 * serves them under the shared owner, beside the views stored under the tenant `(platform)` and the owner `(system)` (which
 * win over a configured one with the same id), and refuses every write to one.
 *
 * The default reads `wow.view-store.system-views`; a host's own bean replaces it. Build each view with
 * [SystemViews.of], which checks it and gives it its content-hash revision.
 */
fun interface SystemViewProvider {
    fun systemViews(tenantId: String, appId: String): Flux<SystemView>
}
