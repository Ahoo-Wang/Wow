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

package me.ahoo.wow.viewstore.server

import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.dsl.filter
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.schema.QueryModel

/**
 * Keeps a caller's queries of the view store's snapshots in its application (the 9.x `ViewStoreQueryPolicy`): a
 * view, preferences or wording written in another application reads as none. A query without an application is
 * refused (`wow.query.malformed`, `APP_REQUIRED`); trusted in-process code, which has no caller, is not restricted.
 *
 * The host applies the same check to the reads by id: a state read (`/state`, `?version=`, the trace)
 * and an events query of an aggregate run it on the aggregate's state, so a request without an application is refused
 * there too, and an aggregate of another application is not found (no events), as a snapshot query would not show it.
 * Its rule reads the state, so it stays [pinned][me.ahoo.wow.query.policy.EventsMode.PINNED]: an events query names its
 * aggregates.
 */
class ViewStoreQueryPolicy : QueryPolicy {
    override suspend fun evaluate(caller: QueryCaller, context: QueryContext<*>): FilterExpression {
        // Every aggregate this service serves is the view store's; the event streams carry no state to restrict by.
        if (caller.caller == null || context.model != QueryModel.SNAPSHOT) {
            return MatchAllFilter
        }
        val app = caller.caller?.app ?: throw QueryRequestException(
            "The request's CoSec scope has no application.",
            APP_REQUIRED,
        )
        return filter { APP_ID eq app }
    }

    companion object {
        /** The state field every view store aggregate keeps its application in. */
        const val APP_ID: String = "state.appId"

        /** The code of a query without an application. */
        const val APP_REQUIRED: String = "APP_REQUIRED"
    }
}
