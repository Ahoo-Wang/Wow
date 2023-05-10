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

package me.ahoo.wow.event.upgrader

import com.fasterxml.jackson.databind.node.ObjectNode
import me.ahoo.wow.serialization.DomainEventRecord
import me.ahoo.wow.serialization.DomainEventRecords.REVISION
import me.ahoo.wow.serialization.MessageRecords.BODY
import me.ahoo.wow.serialization.MessageRecords.BODY_TYPE
import me.ahoo.wow.serialization.MessageRecords.NAME

class MutableDomainEventRecord(override val actual: ObjectNode) : DomainEventRecord {
    override var bodyType: String
        get() = super.bodyType
        set(value) {
            actual.put(BODY_TYPE, value)
        }
    override var name: String
        get() = super.name
        set(value) {
            actual.put(NAME, value)
        }
    override var revision: String
        get() = super.revision
        set(value) {
            actual.put(REVISION, value)
        }

    override var body: ObjectNode
        get() = super.body as ObjectNode
        set(value) {
            actual.set<ObjectNode>(BODY, value)
        }

    companion object {
        fun DomainEventRecord.asMutableDomainEventRecord(): MutableDomainEventRecord {
            if (this is MutableDomainEventRecord) {
                return this
            }
            return MutableDomainEventRecord(actual)
        }
    }
}
