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

package me.ahoo.wow.openapi.contract

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

internal class HttpRouteContractTest {
    @Test
    fun `should expose route key as method and path`() {
        val contract = HttpRouteContract(
            routeId = "wow.command.send",
            method = "POST",
            path = "/wow/command/send",
            handlerKey = "wow.command.send"
        )

        contract.routeKey.assert().isEqualTo("POST /wow/command/send")
    }

    @Test
    fun `should not carry unused route metadata fields`() {
        val fieldNames = HttpRouteContract::class.java.declaredFields.map { it.name }.toSet()

        fieldNames.assert().doesNotContain("category")
        fieldNames.assert().doesNotContain("produce")
        fieldNames.assert().doesNotContain("resourceScope")
    }

    @Test
    fun `should keep parameter model independent from swagger`() {
        val parameter = HttpParameter(
            name = "Command-Type",
            location = HttpParameterLocation.HEADER,
            required = true,
            schema = HttpSchema.String
        )

        parameter.name.assert().isEqualTo("Command-Type")
        parameter.location.assert().isEqualTo(HttpParameterLocation.HEADER)
        parameter.schema.assert().isEqualTo(HttpSchema.String)
    }
}
