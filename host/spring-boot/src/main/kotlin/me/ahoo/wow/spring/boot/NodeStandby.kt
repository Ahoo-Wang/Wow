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

package me.ahoo.wow.spring.boot

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.http.internal.StandbyControl
import me.ahoo.wow.runtime.node.ServiceNode

/** The standby of [node] as the internal port's operator routes see it (`wow.node.standby`). */
@InternalWowApi
class NodeStandby(private val node: ServiceNode) : StandbyControl {
    override fun status(): StandbyControl.Status = StandbyControl.Status(
        node = node.node,
        standby = node.standby,
        resumedAt = node.resumption?.at,
        resumedBy = node.resumption?.by,
        reason = node.resumption?.reason,
    )

    override suspend fun resume(by: String, reason: String?): StandbyControl.Status {
        node.resume(by, reason)
        return status()
    }
}
