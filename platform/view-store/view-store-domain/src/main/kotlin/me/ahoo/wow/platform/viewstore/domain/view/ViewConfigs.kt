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

package me.ahoo.wow.platform.viewstore.domain.view

import me.ahoo.wow.platform.viewstore.api.ViewKind
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.JsonNode

/**
 * The only checks the server makes of a view's config: its `kind` is a view kind, it is no larger than
 * [MAX_CONFIG_BYTES], and the paths a store queries hold what a store can index ([referenceProblem]). The engine admits
 * its meaning when it opens it.
 */
object ViewConfigs {
    const val KIND = "kind"

    /** A dashboard's panels, the array of `config` that references saved views. */
    const val PANELS = "panels"

    /**
     * Where a dashboard panel (an element of [PANELS]) references a saved view, in the engine's `DashboardPanel`: the
     * view it shows (`instanceId`), the view it opens (`opens`), and the view or dashboard a press opens
     * (`click.instanceId`).
     */
    val PANEL_REFERENCES: List<String> = listOf("instanceId", "opens", "click.instanceId")

    /**
     * The largest config, in UTF-8 bytes of its JSON, and the longest title: the port's limits on both sides, which
     * the view engine's `MAX_VIEW_CONFIG_BYTES` and `MAX_VIEW_TITLE_LENGTH` mirror.
     */
    const val MAX_CONFIG_BYTES = 240 * 1024
    const val MAX_TITLE_LENGTH = 120

    /** The longest definition id and panel reference: every store indexes them whole as exact values. */
    const val MAX_ID_LENGTH = 256

    /** The most [PANELS] a config holds. */
    const val MAX_PANELS = 1000

    /** What is wrong with [config], or null when it passes the checks. */
    fun problemOf(config: Map<String, Any?>): String? {
        if (kindOf(config) == null) {
            return "A view's config kind must be one of ${ViewKind.entries.map { it.value }}."
        }
        val tree = WowJson.mapper.valueToTree<JsonNode>(config)
        val bytes = WowJson.mapper.writeValueAsBytes(tree).size
        if (bytes > MAX_CONFIG_BYTES) {
            return "A view's config is $bytes bytes; the limit is $MAX_CONFIG_BYTES."
        }
        return referenceProblem(tree)
    }

    /** The kind of [config], or null when its `kind` names none. */
    fun kindOf(config: Map<String, Any?>): ViewKind? = ViewKind.of(config[KIND])

    /**
     * The paths the shared-board check matches ([PANELS] and its [PANEL_REFERENCES]): when present, `panels` is an
     * array of at most [MAX_PANELS] objects, `click` an object, and each reference a string of at most
     * [MAX_ID_LENGTH] characters. The rest of the config is not read.
     */
    private fun referenceProblem(config: JsonNode): String? {
        val panels = config.get(PANELS)?.takeUnless { it.isNull } ?: return null
        if (!panels.isArray) {
            return "A view's config $PANELS must be an array."
        }
        if (panels.size() > MAX_PANELS) {
            return "A view's config holds ${panels.size()} $PANELS; the limit is $MAX_PANELS."
        }
        return panels.firstNotNullOfOrNull { panel ->
            if (!panel.isObject) {
                "Each of a view's config $PANELS must be an object."
            } else {
                PANEL_REFERENCES.firstNotNullOfOrNull { path -> referenceProblem(panel, path) }
            }
        }
    }

    private fun referenceProblem(panel: JsonNode, path: String): String? {
        var node: JsonNode = panel
        val segments = path.split('.')
        segments.forEachIndexed { index, segment ->
            val value = node.get(segment)?.takeUnless { it.isNull } ?: return null
            if (index == segments.lastIndex) {
                if (!value.isString || value.stringValue().length > MAX_ID_LENGTH) {
                    return "A panel's $path must be a string of at most $MAX_ID_LENGTH characters."
                }
            } else {
                if (!value.isObject) {
                    return "A panel's $segment must be an object."
                }
                node = value
            }
        }
        return null
    }

    /** The views the panels of [config] reference, at any of [PANEL_REFERENCES]. */
    fun referencedViews(config: Map<String, Any?>): Set<String> {
        val panels = config[PANELS] as? List<*> ?: return emptySet()
        return panels.filterIsInstance<Map<*, *>>().flatMap { panel ->
            PANEL_REFERENCES.mapNotNull { path ->
                path.split('.').fold(panel as Any?) { node, segment -> (node as? Map<*, *>)?.get(segment) } as? String
            }
        }.toSet()
    }

    /** What is wrong with [title], or null when, trimmed, it is neither empty nor too long. */
    fun titleProblem(title: String): String? = when {
        title.isBlank() -> "A view's title must not be blank."
        title.trim().length > MAX_TITLE_LENGTH -> "A view's title is longer than $MAX_TITLE_LENGTH characters."
        else -> null
    }

    /** What is wrong with [definitionId], or null when it is neither blank nor too long. */
    fun definitionProblem(definitionId: String): String? = when {
        definitionId.isBlank() -> "A view's definitionId must not be blank."
        definitionId.length > MAX_ID_LENGTH -> "A view's definitionId is longer than $MAX_ID_LENGTH characters."
        else -> null
    }
}
