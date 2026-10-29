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

package me.ahoo.wow.api.query.dsl

import me.ahoo.wow.api.query.QueryField

/**
 * A DSL whose fields may be named under a common path: after `nested("state")`, `"name"` is `state.name`. A path
 * already under it is kept as it is.
 */
@QueryDslMarker
abstract class NestedFieldDsl {
    private var nestedField: String = ""

    fun nested(nestedField: String) {
        this.nestedField = nestedField
    }

    fun nested(nestedField: QueryField<*>) = nested(nestedField.path)

    fun String.withNestedField(): String {
        if (nestedField.isBlank() || startsWith("$nestedField$NESTED_FIELD_DELIMITER")) {
            return this
        }
        return "$nestedField$NESTED_FIELD_DELIMITER$this"
    }

    private companion object {
        const val NESTED_FIELD_DELIMITER = "."
    }
}
