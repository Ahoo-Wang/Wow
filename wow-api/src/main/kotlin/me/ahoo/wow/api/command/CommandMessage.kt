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

package me.ahoo.wow.api.command

import me.ahoo.wow.api.Copyable
import me.ahoo.wow.api.messaging.NamedMessage
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.OwnerId

/**
 * Command Message .
 *
 * @author ahoo wang
 */
interface CommandMessage<C : Any> :
    NamedMessage<CommandMessage<C>, C>,
    AggregateIdCapable,
    NamedAggregate,
    OwnerId,
    CommandId,
    RequestId,
    Copyable<CommandMessage<C>> {
    override val commandId: String
        get() = id

    /**
     * target aggregate id
     */
    override val aggregateId: AggregateId

    /**
     * expected aggregate version
     */
    val aggregateVersion: Int?

    /**
     * is the create aggregate command
     * @see me.ahoo.wow.api.annotation.CreateAggregate
     */
    val isCreate: Boolean

    /**
     * @see me.ahoo.wow.api.annotation.AllowCreate
     */
    val allowCreate: Boolean

    /**
     * @see me.ahoo.wow.api.annotation.VoidCommand
     */
    val isVoid: Boolean
}
