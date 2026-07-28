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

package me.ahoo.wow.runtime

import me.ahoo.wow.runtime.internal.RuntimeOwnershipClaim
import me.ahoo.wow.runtime.internal.StableExclusiveRuntimeOwnership

/**
 * Stable ownership handle for one [RuntimeComponent].
 *
 * A component must retain exactly one handle for its complete lifetime. Runtime
 * claim transactions remain internal; extension code only declares the stable
 * ownership identity used by [WowRuntime].
 */
class RuntimeOwnership private constructor(
    private val claimFactory: (RuntimeComponent) -> RuntimeOwnershipClaim,
) {
    constructor() : this(StableExclusiveRuntimeOwnership()::claim)

    internal fun claim(component: RuntimeComponent): RuntimeOwnershipClaim =
        claimFactory(component)

    companion object {
        internal fun managed(
            claimFactory: (RuntimeComponent) -> RuntimeOwnershipClaim,
        ): RuntimeOwnership =
            RuntimeOwnership(claimFactory)

        internal fun unclaimable(): RuntimeOwnership =
            RuntimeOwnership {
                error("An owner-bound runtime component cannot be claimed again.")
            }
    }
}
