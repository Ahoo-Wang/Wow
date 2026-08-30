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
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import reactor.core.publisher.Mono

class QueryLogErrorHandler : ErrorHandler<QueryContext<*, *>> {
    companion object {
        private val log = KotlinLogging.logger { }
    }

    override fun handle(context: QueryContext<*, *>, throwable: Throwable): Mono<Void> {
        if (throwable is QuerySchemaValidationException) {
            log.error { throwable.message }
        } else {
            log.error(throwable) { throwable.message }
        }
        return Mono.error(throwable)
    }
}
