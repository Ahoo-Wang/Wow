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

package me.ahoo.wow.spi.json

import me.ahoo.wow.api.annotation.InternalWowApi
import tools.jackson.databind.JsonNode

/**
 * The id of the aggregate a command acts on, read from its JSON body by the property a contract names
 * (`idProperty`, the command class's `@AggregateId`): what a sender that has no class of the command reads, or one
 * that reads it as its contract describes it.
 */
@InternalWowApi
object BodyAggregateIds {
    /**
     * The id [body] holds in [property]: null when it leaves it out or holds null there; an
     * [IllegalArgumentException] when it holds anything but a string.
     */
    fun of(body: JsonNode, property: String): String? {
        val value = body.get(property) ?: return null
        if (value.isNull) {
            return null
        }
        require(value.isString) { "The body's $property, the id of its aggregate, is no string: $value." }
        return value.asString()
    }
}
