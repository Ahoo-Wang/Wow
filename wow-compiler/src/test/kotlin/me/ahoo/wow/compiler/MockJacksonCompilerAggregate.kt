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

package me.ahoo.wow.compiler

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import me.ahoo.wow.api.annotation.AggregateRoot

@AggregateRoot
class MockJacksonCompilerAggregate(val state: MockJacksonState)

@JsonIgnoreProperties(value = ["listedIgnored"])
class MockJacksonState(
    val id: String,
    @JsonProperty("plainWire") val plain: String,
    @get:JsonProperty("getterWire") val getter: String,
    @param:JsonProperty("paramWire") val param: String,
    @field:JsonProperty("fieldWire") val field: String,
    @JsonProperty val unnamed: String,
    @JsonProperty("addressWire") val address: MockJacksonAddress,
    @JsonIgnore val ignored: MockJacksonAddress,
    @get:JsonIgnore(false) val notIgnored: String,
    val listedIgnored: String
)

class MockJacksonAddress(
    @JsonProperty("cityWire") val city: String,
    @get:JsonIgnore val secret: String
)
