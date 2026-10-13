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

package me.ahoo.wow.mongo.query

/**
 * How the backend manages the query indexes of the snapshot collections (decision D151), the setting
 * `wow.query.index.ddl`; the same modes as the PostgreSQL backend's, whose type lives in `:postgres` (a backend may
 * not depend on another).
 */
enum class QueryIndexDdl(val value: String) {
    /** Creates the missing indexes, and rebuilds one whose definition differs from the planned one. */
    AUTO("auto"),

    /** Reports the missing and differing indexes, and changes nothing. */
    VALIDATE("validate"),

    /** Does nothing. */
    NONE("none"),
    ;

    companion object {
        /** The setting's name. */
        const val PROPERTY: String = "wow.query.index.ddl"

        /** The mode named [value] (`auto`, `validate` or `none`, in any case). */
        fun of(value: String): QueryIndexDdl = entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
            ?: throw IllegalArgumentException("$PROPERTY must be one of auto, validate or none, not [$value].")
    }
}

/** The options of the MongoDB snapshot query backend. The host maps its configuration onto them (6.2c). */
data class MongoQueryOptions(
    /** How the query indexes are managed: [QueryIndexDdl.AUTO] by default. */
    val indexDdl: QueryIndexDdl = QueryIndexDdl.AUTO,
    /** How many documents a streamed list fetches at a time. */
    val fetchSize: Int = DEFAULT_FETCH_SIZE,
) {
    init {
        require(fetchSize >= 1) { "fetchSize must be at least 1." }
    }

    companion object {
        const val DEFAULT_FETCH_SIZE: Int = 256
    }
}
