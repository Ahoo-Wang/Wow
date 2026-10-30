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

package me.ahoo.wow.query.error

import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.schema.QueryModel

/**
 * An HTTP query whose authenticated scope misses the [field] that the [model] requires, rejected because
 * [QueryEntryPolicy.requireAuthenticatedScope] is on: [QueryErrors.FORBIDDEN]. A declared scope for [field] does not
 * count.
 */
internal class QueryScopeRequiredException(
    val model: QueryModel,
    val field: QueryField<*>,
) : RuntimeException("Query on [$model] requires an authenticated [${field.path}] scope."), QueryError {
    override val errorCode: String
        get() = QueryErrors.FORBIDDEN

    override val errorMsg: String
        get() = checkNotNull(message)
}
