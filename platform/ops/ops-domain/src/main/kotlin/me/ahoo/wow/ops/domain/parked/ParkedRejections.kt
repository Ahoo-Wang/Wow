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

package me.ahoo.wow.ops.domain.parked

import me.ahoo.wow.api.annotation.ErrorCode

/** The codes a [Parked] entry rejects an operator's action with. */
object ParkedRejections {
    @ErrorCode(description = "The entry is resolved: nothing is parked to act on.", status = 409)
    const val RESOLVED = "ops.parked.resolved"

    @ErrorCode(description = "The action's arguments are not valid.")
    const val INVALID = "ops.parked.invalid"
}
