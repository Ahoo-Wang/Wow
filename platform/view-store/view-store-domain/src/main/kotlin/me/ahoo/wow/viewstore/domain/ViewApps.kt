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

package me.ahoo.wow.viewstore.domain

import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.decide.reject
import me.ahoo.wow.viewstore.api.ViewStoreContext

/**
 * The application a command comes from: its standard request fact `app_id` ([RequestFacts.APP_ID]),
 * the application the service's caller resolver authenticated for the request (CoSec's: the app of the request's
 * CoSec scope). A client's own header is never read.
 */
object ViewApps {
    /** The application of [command], or null when its request had none. */
    fun appOf(command: Command<*>): String? = command.headers[RequestFacts.APP_ID]?.trim()?.ifEmpty { null }

    /** Rejects a command whose request names no application. */
    @Suppress("NOTHING_TO_INLINE") // Inline like error(): the rejecting line is the caller's.
    inline fun appRequired(): Nothing = reject(
        ViewStoreRejections.APP_REQUIRED,
        "The request carries no authenticated application (${ViewStoreContext.APP_ID_HEADER}).",
    )

    /**
     * Rejects [command] when it does not come from [stateAppId], the application the aggregate was written in: a
     * command of another application reads the aggregate as not found, so it does not learn that it exists.
     */
    fun rejectOtherApp(command: Command<*>, stateAppId: String) {
        val appId = appOf(command) ?: appRequired()
        if (appId != stateAppId) {
            reject(ViewStoreRejections.NOT_FOUND, "${command.ref.id} is not found.")
        }
    }
}
