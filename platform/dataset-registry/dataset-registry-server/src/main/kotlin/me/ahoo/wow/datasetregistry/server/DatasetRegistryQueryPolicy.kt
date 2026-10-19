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

package me.ahoo.wow.datasetregistry.server

import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.query.QueryRequestException
import me.ahoo.wow.query.filter.QueryContext

/**
 * Keeps the catalog to the registry's roles: a query of the sources (their columns, sensitivity and lineage) by a
 * caller with none of [DatasetRegistryCallers.READERS] is refused ([READER_REQUIRED]); trusted in-process code, which
 * has no caller, is not restricted.
 */
class DatasetRegistryQueryPolicy : QueryPolicy {
    override suspend fun evaluate(caller: QueryCaller, context: QueryContext<*>): FilterExpression {
        val who = caller.caller ?: return MatchAllFilter
        if (DatasetRegistryCallers.rolesOf(who).none { it in DatasetRegistryCallers.READERS }) {
            throw QueryRequestException(
                "${who.subject} reads no source: it needs one of ${DatasetRegistryCallers.READERS}.",
                READER_REQUIRED,
            )
        }
        return MatchAllFilter
    }

    companion object {
        /** The code of a query by a caller without a reader's role. */
        const val READER_REQUIRED: String = "READER_REQUIRED"
    }
}
