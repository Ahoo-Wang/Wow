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
package me.ahoo.wow.modeling.state

import me.ahoo.wow.api.Version
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.api.modeling.DeletedCapable
import me.ahoo.wow.api.modeling.EventIdCapable
import me.ahoo.wow.api.modeling.EventTimeCapable
import me.ahoo.wow.api.modeling.FirstEventTimeCapable
import me.ahoo.wow.api.modeling.FirstOperatorCapable
import me.ahoo.wow.api.modeling.OperatorCapable
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.api.modeling.SpaceIdCapable
import me.ahoo.wow.api.modeling.StateCapable

interface ReadOnlyStateAggregate<S : Any> :
    AggregateIdCapable,
    StateCapable<S>,
    OwnerId,
    SpaceIdCapable,
    Version,
    FirstOperatorCapable,
    OperatorCapable,
    FirstEventTimeCapable,
    EventTimeCapable,
    EventIdCapable,
    DeletedCapable {
    override val aggregateId: AggregateId

    /**
     * 用于生成领域事件版本号.
     */
    override val version: Int

    val expectedNextVersion: Int
        get() = version + 1
}
