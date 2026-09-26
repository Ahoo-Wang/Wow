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

package me.ahoo.wow.query

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.schema.QuerySchemaValidationException

/**
 * Logs every failed query. A rejection from the error catalog, and a server fault the core states
 * ([QueryExecutionException]), is logged by its message alone: a fault's cause can hold record values (a failing mask
 * strategy sees the raw value), so it never reaches the log.
 */
class QueryLogObserver : QueryObserver {
    companion object {
        private val log = KotlinLogging.logger { }
    }

    override fun onError(namedAggregate: NamedAggregate, queryType: QueryType, error: Throwable) {
        val stated = error is QuerySchemaValidationException ||
            error is QueryRequestException ||
            error is QueryExecutionException
        if (stated) {
            log.error { error.message }
        } else {
            log.error(error) { error.message }
        }
    }
}
