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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.http.WowHttpOptions

/**
 * The codes CoSec answers any request with before Wow's routes, beside the public port's own (401, 403, 400): 429
 * `wow.request.rate-limited`, a caller past its rate limit, and 503 `wow.auth.unavailable`, a policy or a permission
 * CoSec cannot read now ([CoSecProblemRenderer.codeOf]). With them among the edge's filter codes, the OpenAPI document
 * lists them on every operation.
 */
@InternalWowApi
object CoSecFilterCodes {
    val CODES: List<String> = listOf(FrameworkCodes.REQUEST_RATE_LIMITED, FrameworkCodes.AUTH_UNAVAILABLE)

    /** [options] with [CODES] after the filter codes it lists. */
    @JvmStatic
    fun addTo(options: WowHttpOptions): WowHttpOptions =
        options.copyWith(filterCodes = (options.filterCodes + CODES).distinct())
}
