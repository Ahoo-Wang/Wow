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

package me.ahoo.wow.viewstore.domain.view

import me.ahoo.wow.viewstore.api.ViewKind
import me.ahoo.wow.viewstore.domain.ViewStoreException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * The only checks the server makes of a view's config: it is an object, its `kind` is a view kind, and it is no
 * larger than [MAX_CONFIG_BYTES]. The engine admits its meaning when it opens it.
 */
object ViewConfigs {
    const val KIND = "kind"
    const val PANELS = "panels"
    const val INSTANCE_ID = "instanceId"
    const val MAX_CONFIG_BYTES = 256 * 1024
    const val MAX_TITLE_LENGTH = 120

    /** The kind of [config], after checking its shape and size. */
    fun requireValid(config: JsonNode?): ViewKind {
        if (config !is ObjectNode) {
            throw ViewStoreException.invalid("A view's config must be an object.")
        }
        val kind = config.get(KIND)?.takeIf { it.isString }?.let { ViewKind.of(it.stringValue()) }
            ?: throw ViewStoreException.invalid(
                "A view's config kind must be one of ${ViewKind.entries.map { it.value }}."
            )
        requireSize(config)
        return kind
    }

    private fun requireSize(config: ObjectNode) {
        val bytes = config.toString().toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_CONFIG_BYTES) {
            throw ViewStoreException.invalid("A view's config is $bytes bytes; the limit is $MAX_CONFIG_BYTES.")
        }
    }

    /** The saved views a dashboard's panels reference; empty for any other kind. */
    fun references(kind: ViewKind, config: ObjectNode): Set<String> {
        if (kind != ViewKind.DASHBOARD) {
            return emptySet()
        }
        val panels = config.get(PANELS)
        if (panels == null || !panels.isArray) {
            return emptySet()
        }
        return panels.mapNotNull { panel ->
            panel.get(INSTANCE_ID)?.takeIf { it.isString }?.stringValue()?.takeIf { it.isNotBlank() }
        }.toSet()
    }

    /** [title] without surrounding blanks, after checking it is neither empty nor too long. */
    fun requireTitle(title: String): String {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            throw ViewStoreException.invalid("A view's title must not be blank.")
        }
        if (trimmed.length > MAX_TITLE_LENGTH) {
            throw ViewStoreException.invalid("A view's title is longer than $MAX_TITLE_LENGTH characters.")
        }
        return trimmed
    }

    fun requireDefinitionId(definitionId: String): String {
        if (definitionId.isBlank()) {
            throw ViewStoreException.invalid("A view's definitionId must not be blank.")
        }
        return definitionId
    }
}
