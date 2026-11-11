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

import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.query.policy.ReadAction
import me.ahoo.wow.query.policy.ReadAuthorization
import me.ahoo.wow.query.policy.ReadAuthorizer

/**
 * Keeps the view store's reads to an application: a read by a caller without one, a query of any model, a state
 * read or a trace, is refused as malformed (`wow.query.malformed`, 400) before anything is read, as the view store's
 * commands refuse it (`APP_REQUIRED`). Who is reading has no application: the request's CoSec scope names none.
 * Which application's views, preferences and wordings a caller then reads is the access policies'
 * ([ViewStoreConfiguration]): each aggregate records its application, `appId`, as an access attribute. Trusted
 * in-process code, which reads with no caller, is not asked.
 */
class ViewStoreApps : ReadAuthorizer {
    override suspend fun authorize(caller: Caller, read: ReadAction): ReadAuthorization =
        if (caller.app != null) {
            ReadAuthorization.Allowed
        } else {
            ReadAuthorization.Denied.of(
                "The request's CoSec scope has no application.",
                FrameworkCodes.QUERY_MALFORMED
            )
        }
}
