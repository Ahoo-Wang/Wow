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

import me.ahoo.cosec.api.context.request.RequestIdCapable
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.http.WowHttpOptions

/**
 * CoSec's request id as a command's request id when the request has no `Wow-Request-Id` (decision D328): a CoSec
 * gateway sets `CoSec-Request-Id` on the requests it forwards, so a command it sends again under the same header is
 * the same request, which the log answers as a duplicate. The header is read as the request carries it, never CoSec's
 * own `Request.requestId`, which CoSec generates for a request without the header.
 */
@InternalWowApi
object CoSecRequestIds {
    const val HEADER: String = RequestIdCapable.REQUEST_ID_KEY

    /** [options] with `CoSec-Request-Id` after the request id headers it lists. */
    @JvmStatic
    fun addTo(options: WowHttpOptions): WowHttpOptions {
        if (HEADER in options.requestIdHeaders) {
            return options
        }
        return options.copyWith(requestIdHeaders = options.requestIdHeaders + HEADER)
    }
}
