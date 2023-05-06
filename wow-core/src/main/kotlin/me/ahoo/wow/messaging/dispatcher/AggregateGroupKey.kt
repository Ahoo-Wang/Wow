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

package me.ahoo.wow.messaging.dispatcher

import me.ahoo.wow.api.Version
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.mod
import me.ahoo.wow.event.DomainEventStream
import reactor.core.scheduler.Schedulers

data class AggregateGroupKey(val key: Int) {

    companion object {
        private const val CREATE_AGGREGATE_KEY = -1
        val CREATE_KEY = AggregateGroupKey(CREATE_AGGREGATE_KEY)
        val AggregateGroupKey.isCreate: Boolean
            get() = this.key == CREATE_AGGREGATE_KEY

        fun AggregateId.asGroupKey(divisor: Int = Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE): AggregateGroupKey {
            return AggregateGroupKey(mod(divisor))
        }

        fun <T : CommandMessage<*>> T.asGroupKey(divisor: Int = Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE): AggregateGroupKey {
            if (isCreate) {
                return CREATE_KEY
            }
            return aggregateId.asGroupKey(divisor)
        }

        fun <T : DomainEventStream> T.asGroupKey(divisor: Int = Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE): AggregateGroupKey {
            if (version == Version.INITIAL_VERSION) {
                return CREATE_KEY
            }
            return aggregateId.asGroupKey(divisor)
        }
    }
}
