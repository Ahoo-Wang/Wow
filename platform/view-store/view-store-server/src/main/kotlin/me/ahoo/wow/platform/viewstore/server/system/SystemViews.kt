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

package me.ahoo.wow.platform.viewstore.server.system

import me.ahoo.wow.platform.viewstore.api.SystemView
import me.ahoo.wow.platform.viewstore.api.SystemViewSource
import me.ahoo.wow.platform.viewstore.domain.view.ViewConfigs
import me.ahoo.wow.spi.json.WowJson
import java.security.MessageDigest

/** Builds system views: checked like a saved view, with a revision that is a hash of their content. */
object SystemViews {
    private const val REVISION_LENGTH = 16

    /**
     * A system view of [source] (at [version] when stored), whose revision changes exactly when its definition, title
     * or config does. A view a saved one could not be is refused (`IllegalArgumentException`).
     */
    fun of(
        id: String,
        definitionId: String,
        title: String,
        config: Map<String, Any?>,
        source: SystemViewSource = SystemViewSource.CONFIGURED,
        version: Int? = null,
    ): SystemView {
        require(id.isNotBlank()) { "A system view's id must not be blank." }
        require(!id.startsWith(SystemView.CODE_SYSTEM_VIEW_PREFIX)) {
            "A system view's id [$id] must not start with [${SystemView.CODE_SYSTEM_VIEW_PREFIX}]: " +
                "the prefix is kept for the views declared in code."
        }
        val problem = ViewConfigs.definitionProblem(definitionId)
            ?: ViewConfigs.titleProblem(title)
            ?: ViewConfigs.problemOf(config)
        require(problem == null) { "System view [$id]: $problem" }
        val trimmed = title.trim()
        return SystemView(
            id = id,
            definitionId = definitionId,
            title = trimmed,
            kind = ViewConfigs.kindOf(config)!!,
            revision = revision(definitionId, trimmed, config),
            config = config,
            source = source,
            version = version,
        )
    }

    private fun revision(definitionId: String, title: String, config: Map<String, Any?>): String {
        val content = listOf(definitionId, title, WowJson.mapper.writeValueAsString(config)).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(REVISION_LENGTH)
    }
}

/**
 * The system views of `wow.view-store.system-views`, checked once at startup: a view a saved one could not be, or one
 * configured twice for the same tenant and application, stops the service. An entry with a blank tenant or
 * application is for every tenant or application. They are not aggregates, so no route writes them.
 */
class ConfiguredSystemViews(properties: List<SystemViewProperties>) {
    private val entries: List<Entry> = properties.map { property ->
        @Suppress("UNCHECKED_CAST")
        val config = runCatching { WowJson.mapper.readValue(property.config, Map::class.java) as Map<String, Any?> }
            .getOrElse {
                throw IllegalArgumentException(
                    "System view [${property.id}]'s config is not a JSON object.",
                    it
                )
            }
        Entry(
            property.tenantId,
            property.appId,
            SystemViews.of(property.id, property.definitionId, property.title, config)
        )
    }

    init {
        entries.groupBy { Triple(it.tenantId, it.appId, it.view.id) }.values.firstOrNull { it.size > 1 }?.let {
            throw IllegalArgumentException("System view [${it.first().view.id}] is configured more than once.")
        }
    }

    /** The configured system views of [tenantId] and [appId], in the order they are configured. */
    fun systemViews(tenantId: String, appId: String): List<SystemView> =
        entries.filter { it.matches(tenantId, appId) }.map { it.view }

    private data class Entry(val tenantId: String, val appId: String, val view: SystemView) {
        fun matches(tenantId: String, appId: String): Boolean =
            (this.tenantId.isBlank() || this.tenantId == tenantId) && (this.appId.isBlank() || this.appId == appId)
    }
}
