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

package me.ahoo.wow.serialization.command

import me.ahoo.wow.serialization.MessageAggregateIdRecord
import me.ahoo.wow.serialization.MessageAggregateNameRecord
import me.ahoo.wow.serialization.MessageBodyTypeRecord
import me.ahoo.wow.serialization.MessageNameRecord
import me.ahoo.wow.serialization.MessageRequestIdRecord
import me.ahoo.wow.serialization.NamedBoundedContextMessageRecord
import me.ahoo.wow.serialization.OwnerIdRecord
import me.ahoo.wow.serialization.SpaceIdRecord
import tools.jackson.databind.node.ObjectNode

object CommandRecords {
    const val AGGREGATE_VERSION = "aggregateVersion"
    const val IS_CREATE = "isCreate"
    const val IS_VOID = "isVoid"
    const val ALLOW_CREATE = "allowCreate"
}

interface CommandRecord :
    NamedBoundedContextMessageRecord,
    MessageNameRecord,
    MessageRequestIdRecord,
    MessageAggregateNameRecord,
    MessageAggregateIdRecord,
    OwnerIdRecord,
    SpaceIdRecord,
    MessageBodyTypeRecord {
    val aggregateVersion: Int?
        get() = actual.get(CommandRecords.AGGREGATE_VERSION)?.asInt()
    val isCreate: Boolean
        get() = actual[CommandRecords.IS_CREATE].asBoolean()
    val allowCreate: Boolean
        get() = actual.get(CommandRecords.ALLOW_CREATE)?.asBoolean() == true
    val isVoid: Boolean
        get() = actual.get(CommandRecords.IS_VOID)?.asBoolean() == true
}

class DelegatingCommandRecord(override val actual: ObjectNode) : CommandRecord

fun ObjectNode.toCommandRecord(): CommandRecord {
    return DelegatingCommandRecord(this)
}
