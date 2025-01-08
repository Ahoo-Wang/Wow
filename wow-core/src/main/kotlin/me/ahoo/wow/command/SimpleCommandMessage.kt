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

package me.ahoo.wow.command

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.naming.annotation.toName

data class SimpleCommandMessage<C : Any>(
    override val id: String = GlobalIdGenerator.generateAsString(),
    override val header: Header = DefaultHeader.empty(),
    override val body: C,
    override val aggregateId: AggregateId,
    override val requestId: String = id,
    override val aggregateVersion: Int? = null,
    override val name: String = body.javaClass.toName(),
    override val isCreate: Boolean = false,
    override val allowCreate: Boolean = false,
    override val isVoid: Boolean = false,
    override val createTime: Long = System.currentTimeMillis()
) : CommandMessage<C>, NamedAggregate by aggregateId {
    override fun copy(): CommandMessage<C> {
        return copy(header = header.copy())
    }
}
