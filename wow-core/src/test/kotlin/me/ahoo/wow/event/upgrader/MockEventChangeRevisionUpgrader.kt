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

import me.ahoo.wow.api.annotation.ORDER_FIRST
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.event.upgrader.MutableDomainEventRecord.Companion.asMutableDomainEventRecord
import me.ahoo.wow.serialization.event.DomainEventRecord

@Order(ORDER_FIRST)
class MockEventChangeRevisionUpgrader : EventUpgrader {
    companion object {
        const val REVISION = "1.0.0"
    }

    override val eventNamedAggregate: EventNamedAggregate
        get() = MockEventToDroppedUpgrader.EVENT_NAMED_AGGREGATE

    override fun upgrade(domainEventRecord: DomainEventRecord): DomainEventRecord {
        val mutableDomainEventRecord = domainEventRecord.asMutableDomainEventRecord()
        mutableDomainEventRecord.revision = REVISION
        return mutableDomainEventRecord
    }
}
