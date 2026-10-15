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

package me.ahoo.wow.cosec

import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.DeviceIdCapable
import me.ahoo.wow.http.WowHttpOptions

/**
 * The CoSec request headers a command keeps as request facts, as 9.x wow-cosec appended them: the app and the device
 * the request came from. The edge copies them through its request-fact list ([WowHttpOptions.requestFacts]).
 */
internal object CoSecRequestFacts {
    const val APP_ID: String = AppIdCapable.APP_ID_KEY
    const val DEVICE_ID: String = DeviceIdCapable.DEVICE_ID_KEY

    @JvmField
    val NAMES: Set<String> = setOf(APP_ID, DEVICE_ID)

    /** [options] with the CoSec request facts added to the ones it lists. */
    @JvmStatic
    fun addTo(options: WowHttpOptions): WowHttpOptions {
        if (options.requestFacts.containsAll(NAMES)) {
            return options
        }
        return options.copy(requestFacts = options.requestFacts + NAMES)
    }
}
