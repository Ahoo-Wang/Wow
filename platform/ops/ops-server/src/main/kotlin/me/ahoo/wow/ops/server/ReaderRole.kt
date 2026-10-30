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

package me.ahoo.wow.ops.server

import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.policy.EventsMode
import me.ahoo.wow.query.policy.QueryPolicy

/**
 * Who reads the entries where the roles decide: a caller with one of [OpsRoles.READERS] reads every entry of its
 * tenant, and any other caller's query of them, a count, a list, an aggregation, their event streams, a state read or
 * a trace, is refused as `wow.auth.forbidden` ([READER_REQUIRED]); trusted in-process code, which has no caller, is not
 * restricted. Every aggregate this service serves is an entry.
 *
 * An entry is a resource of its tenant (`Ownership.TENANT`): no caller's owner restricts its reads, so the scope
 * policy lets every signed-in caller of the tenant read them all, an owner-level one (a principal with no ops role)
 * as a tenant-level one. The roles are what keeps them to the operators and viewers. It hides no row, so the entries'
 * events queries need not name their aggregates ([EventsMode.NONE]).
 */
class ReaderRole : QueryPolicy {
    override val events: EventsMode
        get() = EventsMode.NONE

    override suspend fun evaluate(caller: QueryCaller, context: QueryContext<*>): FilterExpression {
        val who = caller.caller ?: return MatchAllFilter
        if (OpsRoles.rolesOf(who).none { it in OpsRoles.READERS }) {
            throw QueryRequestException(
                "${who.subject ?: "An anonymous caller"} reads no parked entry: it needs ${OpsRoles.VIEWER} or " +
                    "${OpsRoles.OPERATOR}.",
                READER_REQUIRED,
                problem = FrameworkCodes.AUTH_FORBIDDEN,
            )
        }
        return MatchAllFilter
    }

    companion object {
        /** The violation code of a query by a caller without a reader's role. */
        const val READER_REQUIRED: String = "READER_REQUIRED"

        /** No restriction, where CoSec decides the requests and its policies of the query routes say who reads. */
        @JvmField
        val COSEC_DECIDES: QueryPolicy = object : QueryPolicy {
            override val events: EventsMode
                get() = EventsMode.NONE

            override suspend fun evaluate(caller: QueryCaller, context: QueryContext<*>): FilterExpression =
                MatchAllFilter
        }
    }
}
