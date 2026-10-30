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

import me.ahoo.cosec.runtime.http.CoSecParameters
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.http.WowHttpOptions

/**
 * The CoSec request header a command keeps as a request fact, as 9.x wow-cosec appended it: the device the request
 * came from. The edge copies it through its request-fact list ([WowHttpOptions.requestFacts]). The application,
 * `CoSec-App-Id`, is not copied as the client sent it: the command records the app of the scope CoSec resolved for
 * the request as the standard request fact `app_id` ([CoSecCallerResolver]).
 */
@InternalWowApi
object CoSecRequestFacts {
    const val DEVICE_ID: String = CoSecParameters.DEVICE_ID

    @JvmField
    val NAMES: Set<String> = setOf(DEVICE_ID)

    /** [options] with the CoSec request facts added to the ones it lists. */
    @JvmStatic
    fun addTo(options: WowHttpOptions): WowHttpOptions {
        if (options.requestFacts.containsAll(NAMES)) {
            return options
        }
        return options.copyWith(requestFacts = options.requestFacts + NAMES)
    }
}
