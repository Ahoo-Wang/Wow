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

package me.ahoo.wow.client

import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.json.JsonMapper

/**
 * The JSON of the clients and of the wire grammar both ends share: Wow's one configuration ([WowJson], decisions D105,
 * D122), as the public port's, so a decimal in a peer's results or a rejection's extensions keeps its exact value
 * (review A5-6).
 */
internal object ClientJson {
    val mapper: JsonMapper = WowJson.builder().build()

    fun write(value: Any): ByteArray = mapper.writeValueAsBytes(value)
}
