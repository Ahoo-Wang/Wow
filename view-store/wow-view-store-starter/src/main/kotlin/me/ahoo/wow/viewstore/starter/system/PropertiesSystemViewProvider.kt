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

import me.ahoo.wow.serialization.toObject
import me.ahoo.wow.viewstore.api.SystemView
import me.ahoo.wow.viewstore.starter.SystemViewProperties
import reactor.core.publisher.Flux
import tools.jackson.databind.node.ObjectNode

/**
 * The system views of `wow.view-store.system-views`, checked once at startup. An entry with a blank tenant or
 * application is for every tenant or application.
 */
class PropertiesSystemViewProvider(properties: List<SystemViewProperties>) : SystemViewProvider {
    private val entries: List<Entry> = properties.map { property ->
        val config = runCatching { property.config.toObject<ObjectNode>() }.getOrElse {
            throw IllegalArgumentException("System view [${property.id}]'s config is not a JSON object.", it)
        }
        Entry(
            tenantId = property.tenantId,
            appId = property.appId,
            view = SystemViews.of(property.id, property.definitionId, property.title, config),
        )
    }

    init {
        entries.groupBy { Triple(it.tenantId, it.appId, it.view.id) }.values.firstOrNull { it.size > 1 }?.let {
            throw IllegalArgumentException("System view [${it.first().view.id}] is configured more than once.")
        }
    }

    override fun systemViews(tenantId: String, appId: String): Flux<SystemView> = Flux.fromIterable(
        entries.filter { it.matches(tenantId, appId) }.map { it.view }
    )

    private data class Entry(val tenantId: String, val appId: String, val view: SystemView) {
        fun matches(tenantId: String, appId: String): Boolean =
            (this.tenantId.isBlank() || this.tenantId == tenantId) && (this.appId.isBlank() || this.appId == appId)
    }
}
