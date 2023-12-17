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

package me.ahoo.wow.serialization.event

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.node.ObjectNode
import me.ahoo.wow.event.DomainEventStream

object EventStreamJsonSerializer : AbstractEventStreamJsonSerializer<DomainEventStream>(DomainEventStream::class.java)

object EventStreamJsonDeserializer : StdDeserializer<DomainEventStream>(DomainEventStream::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): DomainEventStream {
        return p.codec.readTree<ObjectNode>(p).toEventStreamRecord().toDomainEventStream()
    }
}
