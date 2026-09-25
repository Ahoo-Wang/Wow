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

package me.ahoo.wow.query.schema

import me.ahoo.wow.api.query.annotation.SensitivityLevel

/**
 * How the query subsystem treats sensitive fields beyond their declared level.
 *
 * @property displayComparable whether filters and paged sorts may compare the raw value of a
 * [SensitivityLevel.DISPLAY] field. On by default; turning it off treats DISPLAY fields like
 * [SensitivityLevel.CONFIDENTIAL] ones for comparison, so range conditions cannot approach their values.
 */
data class QuerySensitivityPolicy(val displayComparable: Boolean = true) {
    /** Whether a filter or sort may compare a value protected at [level]; unprotected values always compare. */
    fun comparable(level: SensitivityLevel?): Boolean = when (level) {
        null -> true
        SensitivityLevel.DISPLAY -> displayComparable
        SensitivityLevel.CONFIDENTIAL -> false
    }

    companion object {
        val DEFAULT = QuerySensitivityPolicy()
    }
}
