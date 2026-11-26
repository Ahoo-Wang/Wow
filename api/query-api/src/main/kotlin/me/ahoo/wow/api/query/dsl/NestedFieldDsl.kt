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
 * A DSL whose fields may be named under a common path, as the filter DSL's are: within `"state".path { … }`, `"name"`
 * is `state.name`. A path already under it is kept as it is, and a name equal to it is under it: `"state"` is
 * `state.state` ([QueryField.absoluteTo]). Paths nest: a path within a path is under both.
 */
@QueryDslMarker
abstract class NestedFieldDsl internal constructor() {
    /** The field this block's names are under, while a `path` block runs; `null` outside one. */
    private var nestedField: QueryField<*>? = null

    /** Runs [block] with its names under [path], within the path a block already runs under. */
    internal fun <D : NestedFieldDsl> D.under(path: QueryField<*>, block: D.() -> Unit) {
        val outer = nestedField
        nestedField = path.absoluteTo(outer)
        try {
            block()
        } finally {
            nestedField = outer
        }
    }

    /** This path in the block's terms: under its nested field, as [QueryField.absoluteTo] places it. */
    internal fun String.withNestedField(): String = QueryField(this).absoluteTo(nestedField).path
}
