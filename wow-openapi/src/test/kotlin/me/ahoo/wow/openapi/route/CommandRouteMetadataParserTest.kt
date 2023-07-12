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

package me.ahoo.wow.openapi.route

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.node.ObjectNode
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.serialization.JsonSerializer
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class CommandRouteMetadataParserTest {

    @Test
    fun asCommandRouteMetadata() {
        val commandRouteMetadata = commandRouteMetadata<MockCommandRoute>()
        assertThat(commandRouteMetadata.path, equalTo("{id}/{name}"))
        assertThat(commandRouteMetadata.enabled, equalTo(true))
        assertThat(commandRouteMetadata.ignoreAggregateNamePrefix, equalTo(false))
        val idPathVariable = commandRouteMetadata.pathVariableMetadata.first { it.pathVariableName == "id" }
        assertThat(idPathVariable.fieldName, equalTo("id"))
        assertThat(idPathVariable.pathVariableName, equalTo("id"))
        assertThat(idPathVariable.required, equalTo(true))

        val namePathVariable = commandRouteMetadata.pathVariableMetadata.first { it.pathVariableName == "name" }
        assertThat(namePathVariable.fieldName, equalTo("customName"))
        assertThat(namePathVariable.pathVariableName, equalTo("name"))
        assertThat(namePathVariable.required, equalTo(false))
    }

    @Test
    fun decode() {
        val commandRouteMetadata = commandRouteMetadata<MockCommandRoute>()
        val command = commandRouteMetadata.decode(
            ObjectNode(JsonSerializer.nodeFactory),
            mapOf(
                "id" to "id",
                "name" to "name",
            ),
        )
        assertThat(command.id, equalTo("id"))
        assertThat(command.name, equalTo("name"))

        val commandWithDefault = commandRouteMetadata.decode(
            ObjectNode(JsonSerializer.nodeFactory),
            mapOf(
                "id" to "id",
            ),
        )
        assertThat(commandWithDefault.id, equalTo("id"))
        assertThat(commandWithDefault.name, equalTo("otherName"))
    }

    @Test
    fun decodeNested() {
        val commandRouteMetadata = commandRouteMetadata<NestedMockCommandRoute>()
        assertThat(commandRouteMetadata.path, equalTo("{customerId}/{id}"))
        val customerIdPathVariable =
            commandRouteMetadata.pathVariableMetadata.first { it.pathVariableName == "customerId" }
        assertThat(customerIdPathVariable.fieldName, equalTo("id"))
        assertThat(customerIdPathVariable.fieldPath, equalTo(listOf("customer", "id")))
        val command = commandRouteMetadata.decode(
            ObjectNode(JsonSerializer.nodeFactory),
            mapOf(
                "id" to "id",
                "customerId" to "customerId",
            ),
        )
        assertThat(command.id, equalTo("id"))
        assertThat(command.customer.id, equalTo("customerId"))
    }
}

@CommandRoute("{id}/{name}")
data class MockCommandRoute(
    @CommandRoute.PathVariable
    val id: String,
    @field:JsonProperty("customName")
    @CommandRoute.PathVariable(name = "name", required = false)
    val name: String = "otherName"
)

@CommandRoute("{customerId}/{id}")
data class NestedMockCommandRoute(
    @CommandRoute.PathVariable
    val id: String,
    @CommandRoute.PathVariable(name = "customerId", nestedPath = ["id"])
    val customer: Customer
) {
    data class Customer(val id: String)
}
