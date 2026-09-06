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

package me.ahoo.wow.schema.query

import com.fasterxml.jackson.annotation.JsonIgnore
import me.ahoo.wow.api.query.mask.Mask
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.module.SimpleModule
import tools.jackson.databind.node.ObjectNode

data class RegisteredMaskedValue(
    @field:Mask val secret: String,
    @field:JsonIgnore val received: String = "native-bean-deserializer",
)

data class RegisteredPropertyState(val value: RegisteredMaskedValue)

data class RegisteredListState(val values: List<RegisteredMaskedValue>)

class RegisteredMaskTestModule : SimpleModule() {
    init {
        addDeserializer(RegisteredMaskedValue::class.java, RegisteredMaskedValueDeserializer())
    }
}

private class RegisteredMaskedValueDeserializer : StdDeserializer<RegisteredMaskedValue>(
    RegisteredMaskedValue::class.java,
) {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): RegisteredMaskedValue {
        val received = parser.readValueAsTree<ObjectNode>().path("secret").stringValue()
        return RegisteredMaskedValue(secret = received, received = received)
    }
}
