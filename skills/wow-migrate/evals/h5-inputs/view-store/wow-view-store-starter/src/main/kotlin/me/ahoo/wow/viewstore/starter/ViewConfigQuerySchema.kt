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

package me.ahoo.wow.viewstore.starter

import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.schema.QueryFieldDeclarationBuilder
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.schema.querySchemaRegistration
import me.ahoo.wow.viewstore.domain.view.View
import me.ahoo.wow.viewstore.domain.view.ViewConfigs

/**
 * Opens the parts of a view's `config` that the view store queries: `config.kind` (a list reads it without the
 * rest of the config, and the shared-board check filters on it) and the panel references of a dashboard
 * ([ViewConfigs.PANEL_REFERENCES] within `config.panels[]`, matched per element).
 *
 * `config` is an `ObjectNode`, which Wow's schema inference records as an opaque value (kind `UNKNOWN`); declaring
 * it `OBJECT` opens it, so its properties can be declared.
 */
internal object ViewConfigQuerySchema {
    const val CONFIG_FIELD = "state.config"

    fun registration(): QuerySchemaRegistration = querySchemaRegistration(View::class, QueryModel.SNAPSHOT) {
        field(CONFIG_FIELD) {
            kind(QueryValueKind.OBJECT)
            property(ViewConfigs.KIND) { types(QueryValueType.STRING) }
            property(ViewConfigs.PANELS) {
                items {
                    ViewConfigs.PANEL_REFERENCES.forEach { declareString(it) }
                }
            }
        }
    }

    /** Declares the string at [path] (dotted for a nested object) within the current declaration. */
    private fun QueryFieldDeclarationBuilder.declareString(path: String) {
        val head = path.substringBefore('.')
        if (head == path) {
            property(head) { types(QueryValueType.STRING) }
        } else {
            property(head) { declareString(path.substringAfter('.')) }
        }
    }
}
