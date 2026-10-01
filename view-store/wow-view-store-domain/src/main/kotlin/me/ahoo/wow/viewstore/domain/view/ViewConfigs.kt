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
 * The only checks the server makes of a view's config: it is an object, its `kind` is a view kind, it is no larger
 * than [MAX_CONFIG_BYTES], and the paths the store queries hold what a store can index ([requireReferences]). The
 * engine admits its meaning when it opens it.
 */
object ViewConfigs {
    const val KIND = "kind"

    /** A dashboard's panels, the array of `config` that references saved views. */
    const val PANELS = "panels"

    /**
     * Where a dashboard panel (an element of [PANELS]) references a saved view, in the engine's `DashboardPanel`:
     * the view it shows (`instanceId`), the view 「在工作台中打开」 opens (`opens`), and the view or dashboard a press
     * opens (`click.instanceId`). A board's tabs hold no panels, and a view it owns (`owned`) is not a reference.
     */
    val PANEL_REFERENCES: List<String> = listOf("instanceId", "opens", "click.instanceId")
    const val MAX_CONFIG_BYTES = 256 * 1024
    const val MAX_TITLE_LENGTH = 120

    /**
     * The longest definition id and panel reference: every store indexes them whole as exact values, and an index
     * term has a size limit (Elasticsearch refuses a whole document whose keyword passes 32766 bytes).
     */
    const val MAX_ID_LENGTH = 256

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
        requireReferences(config)
        return kind
    }

    /**
     * The paths the shared-board check matches ([PANELS] and its [PANEL_REFERENCES]) are mapped by type in a store
     * with typed mappings (Elasticsearch), where a value of another type would fail the whole snapshot write: when
     * present, `panels` is an array of objects, `click` an object, and each reference a string of at most
     * [MAX_ID_LENGTH] characters. The rest of the config is not read.
     */
    private fun requireReferences(config: ObjectNode) {
        val panels = config.get(PANELS)?.takeUnless { it.isNull } ?: return
        if (!panels.isArray) {
            throw ViewStoreException.invalid("A view's config $PANELS must be an array.")
        }
        panels.forEach { panel ->
            if (!panel.isObject) {
                throw ViewStoreException.invalid("Each of a view's config $PANELS must be an object.")
            }
            PANEL_REFERENCES.forEach { path -> requireReference(panel, path) }
        }
    }

    private fun requireReference(panel: JsonNode, path: String) {
        var node: JsonNode = panel
        val segments = path.split('.')
        segments.forEachIndexed { index, segment ->
            val value = node.get(segment)?.takeUnless { it.isNull } ?: return
            if (index == segments.lastIndex) {
                if (!value.isString || value.stringValue().length > MAX_ID_LENGTH) {
                    throw ViewStoreException.invalid(
                        "A panel's $path must be a string of at most $MAX_ID_LENGTH characters."
                    )
                }
            } else {
                if (!value.isObject) {
                    throw ViewStoreException.invalid("A panel's $segment must be an object.")
                }
                node = value
            }
        }
    }

    private fun requireSize(config: ObjectNode) {
        val bytes = config.toString().toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_CONFIG_BYTES) {
            throw ViewStoreException.invalid("A view's config is $bytes bytes; the limit is $MAX_CONFIG_BYTES.")
        }
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
        if (definitionId.length > MAX_ID_LENGTH) {
            throw ViewStoreException.invalid("A view's definitionId is longer than $MAX_ID_LENGTH characters.")
        }
        return definitionId
    }
}
