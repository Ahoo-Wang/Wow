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

package me.ahoo.wow.platform.viewstore.domain

import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.platform.viewstore.api.ViewStoreContext

/**
 * The application a command comes from: the request's `CoSec-App-Id`, which the service carries into the command's
 * headers as a request fact. Header names are matched without regard to case, as HTTP matches them.
 */
object ViewApps {
    /** The application of [command], or null when its request named none. */
    fun appOf(command: Command<*>): String? = command.headers.entries
        .firstOrNull { it.key.equals(ViewStoreContext.APP_ID_HEADER, ignoreCase = true) }
        ?.value?.trim()?.ifEmpty { null }

    /** The rejection of a command whose request names no application. */
    fun appRequired(): Decision =
        Decision.reject(ViewStoreRejections.APP_REQUIRED, "The request carries no ${ViewStoreContext.APP_ID_HEADER}.")

    /**
     * The rejection of [command] when it does not come from [stateAppId], the application the aggregate was written
     * in: a command of another application reads the aggregate as not found, so it does not learn that it exists.
     * Null when it comes from that application.
     */
    fun otherApp(command: Command<*>, stateAppId: String): Decision? {
        val appId = appOf(command) ?: return appRequired()
        if (appId == stateAppId) {
            return null
        }
        return Decision.reject(ViewStoreRejections.NOT_FOUND, "${command.ref.id} is not found.")
    }

    /** The rejection of an invalid write, saying why. */
    fun invalid(message: String): Decision = Decision.reject(ViewStoreRejections.INVALID, message)
}
