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

package me.ahoo.wow.query.json

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.json.query.QueryProtocolJson
import tools.jackson.databind.JacksonModule
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.module.SimpleModule

/**
 * The JSON of the query engine: the query protocol's ([QueryProtocolJson], in `:spi`, decision D293) and the capability
 * descriptor's ([QueryModelDescriptor][me.ahoo.wow.query.descriptor.QueryModelDescriptor], the `GET` of the query
 * route), as 9.x wrote it: models and value types as strings, semantic types discriminated by `type`.
 */
@InternalWowApi
object QueryJson {
    /** The Jackson module of the capability descriptor, which the protocol's module does not hold. */
    val module: JacksonModule = SimpleModule(MODULE_NAME).apply { registerDescriptorJson() }

    /** `WowJson.builder().addModule(QueryProtocolJson.module).addModule(module).build()` */
    val mapper: JsonMapper = WowJson.builder().addModule(QueryProtocolJson.module).addModule(module).build()

    private const val MODULE_NAME = "wow-query-descriptor-json"
}
