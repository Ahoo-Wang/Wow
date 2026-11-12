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

package me.ahoo.wow.viewstore.api

import com.fasterxml.jackson.annotation.JsonValue

/**
 * The kind of a saved view: the `kind` of its config, as the view engine writes it.
 *
 * Cases may be added in a minor release, so a `when` over it keeps an `else` branch.
 *
 * The view store's events carry its kind, so a new case is read by every node of the view store, and by the view
 * engine, before one writes it. An older node fails on an event of a kind it does not know, and refuses to save a view
 * of one ([ViewKind.of] finds none). An older view engine does not check the kind: it reads one that is neither
 * `record` nor `dashboard` as an analysis.
 */
enum class ViewKind(@get:JsonValue val value: String) {
    RECORD("record"),
    ANALYSIS("analysis"),
    DASHBOARD("dashboard"),
    ;

    companion object {
        /** The kind named [value], or `null` when no kind has that name. */
        fun of(value: Any?): ViewKind? = entries.firstOrNull { it.value == value }
    }
}
