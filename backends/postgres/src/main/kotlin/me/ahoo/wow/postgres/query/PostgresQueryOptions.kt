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

package me.ahoo.wow.postgres.query

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.Ddl

/** The options of the PostgreSQL snapshot query backend. The host maps its configuration onto them (6.2c).  *
 * Fields may be added (decision D216): Wow builds it from the host's configuration, so the constructor is internal
 * to Wow.
 */
class PostgresQueryOptions @InternalWowApi constructor(
    /** How the expression indexes are managed: [Ddl.AUTO] by default. */
    val indexDdl: Ddl = Ddl.AUTO,
    /** How many rows a streamed list fetches at a time. */
    val fetchSize: Int = DEFAULT_FETCH_SIZE,
) {
    init {
        require(fetchSize >= 1) { "fetchSize must be at least 1." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is PostgresQueryOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "PostgresQueryOptions(indexDdl=$indexDdl, fetchSize=$fetchSize)"

    private fun valueFields(): List<Any?> = listOf(indexDdl, fetchSize)

    companion object {
        const val DEFAULT_FETCH_SIZE: Int = 256
    }
}
