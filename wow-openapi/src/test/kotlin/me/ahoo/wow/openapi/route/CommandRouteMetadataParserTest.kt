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
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.serialization.JsonSerializer
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class CommandRouteMetadataParserTest {

    @Test
    fun asCommandRouteMetadata() {
        val commandRouteMetadata = commandRouteMetadata<MockCommandRoute>()
        assertThat(commandRouteMetadata.enabled, equalTo(true))
        assertThat(commandRouteMetadata.path, equalTo("{id}/{name}"))
        assertThat(commandRouteMetadata.method, equalTo(Https.Method.PATCH))
        assertThat(commandRouteMetadata.prefix, equalTo(""))
        assertThat(commandRouteMetadata.appendIdPath, equalTo(CommandRoute.AppendPath.DEFAULT))
        assertThat(commandRouteMetadata.ignoreAggregateNamePrefix, equalTo(false))
        val idPathVariable = commandRouteMetadata.pathVariableMetadata.first { it.variableName == "id" }
        assertThat(idPathVariable.fieldName, equalTo("id"))
        assertThat(idPathVariable.variableName, equalTo("id"))
        assertThat(idPathVariable.required, equalTo(true))

        val namePathVariable = commandRouteMetadata.pathVariableMetadata.first { it.variableName == "name" }
        assertThat(namePathVariable.fieldName, equalTo("customName"))
        assertThat(namePathVariable.variableName, equalTo("name"))
        assertThat(namePathVariable.required, equalTo(false))

        val headerVariable = commandRouteMetadata.headerVariableMetadata.first { it.variableName == "header" }
        assertThat(headerVariable.fieldName, equalTo("header"))
        assertThat(headerVariable.variableName, equalTo("header"))
        assertThat(headerVariable.required, equalTo(false))
    }

    @Test
    fun asDelete() {
        val commandRouteMetadata = commandRouteMetadata<DefaultDeleteAggregate>()
        assertThat(commandRouteMetadata.method, equalTo(Https.Method.DELETE))
    }

    @Test
    fun decode() {
        val commandRouteMetadata = commandRouteMetadata<MockCommandRoute>()
        val command = commandRouteMetadata.decode(
            ObjectNode(JsonSerializer.nodeFactory),
            {
                mapOf(
                    "id" to "id",
                    "name" to "name",
                )[it]
            },
            {
                mapOf(
                    "header" to "header-value",
                )[it]
            }
        )
        assertThat(command.id, equalTo("id"))
        assertThat(command.name, equalTo("name"))
        assertThat(command.header, equalTo("header-value"))

        val commandWithDefault = commandRouteMetadata.decode(
            ObjectNode(JsonSerializer.nodeFactory),
            {
                mapOf(
                    "id" to "id",
                )[it]
            },
            {
                null
            }
        )
        assertThat(commandWithDefault.id, equalTo("id"))
        assertThat(commandWithDefault.name, equalTo("otherName"))
        assertThat(commandWithDefault.header, equalTo("header"))
    }

    @Test
    fun decodeNested() {
        val commandRouteMetadata = commandRouteMetadata<NestedMockCommandRoute>()
        assertThat(commandRouteMetadata.path, equalTo("{customerId}/{id}/{name}"))
        val customerIdPathVariable =
            commandRouteMetadata.pathVariableMetadata.first { it.variableName == "customerId" }
        assertThat(customerIdPathVariable.fieldName, equalTo("id"))
        assertThat(customerIdPathVariable.fieldPath, equalTo(listOf("customer", "id")))

        val command = commandRouteMetadata.decode(
            ObjectNode(JsonSerializer.nodeFactory),
            {
                mapOf(
                    "id" to "id",
                    "customerId" to "customerId",
                    "name" to "name",
                )[it]
            },
            {
                null
            }
        )
        assertThat(command.id, equalTo("id"))
        assertThat(command.customer.id, equalTo("customerId"))
        assertThat(command.customer.name, equalTo("name"))
    }

    @Test
    fun decodeFieldNested() {
        val commandRouteMetadata = commandRouteMetadata<NestedFieldMockCommandRoute>()
        assertThat(commandRouteMetadata.path, equalTo("{customerId}/{id}/{name}"))
        val customerIdPathVariable =
            commandRouteMetadata.pathVariableMetadata.first { it.variableName == "customerId" }
        assertThat(customerIdPathVariable.fieldName, equalTo("id"))
        assertThat(customerIdPathVariable.fieldPath, equalTo(listOf("customer", "id")))

        val command = commandRouteMetadata.decode(
            ObjectNode(JsonSerializer.nodeFactory),
            {
                mapOf(
                    "id" to "id",
                    "customerId" to "customerId",
                    "name" to "name",
                )[it]
            },
            {
                null
            }
        )
        assertThat(command.id, equalTo("id"))
        assertThat(command.customer.id, equalTo("customerId"))
        assertThat(command.customer.name, equalTo("name"))
    }
}

@CommandRoute("{id}/{name}", method = CommandRoute.Method.PATCH)
data class MockCommandRoute(
    @CommandRoute.PathVariable
    val id: String,
    @field:JsonProperty("customName")
    @CommandRoute.PathVariable(name = "name", required = false)
    val name: String = "otherName",
    @CommandRoute.HeaderVariable(name = "header", required = false)
    val header: String = "header",
)

@CommandRoute("{customerId}/{id}/{name}")
data class NestedMockCommandRoute(
    @CommandRoute.PathVariable
    val id: String,
    @CommandRoute.PathVariable(name = "customerId", nestedPath = ["id"])
    @CommandRoute.PathVariable(name = "name", nestedPath = ["name"])
    val customer: Customer
) {
    data class Customer(val id: String, val name: String)
}

@CommandRoute("{customerId}/{id}/{name}")
data class NestedFieldMockCommandRoute(
    @field:CommandRoute.PathVariable
    val id: String,
    @field:CommandRoute.PathVariable(name = "customerId", nestedPath = ["id"])
    @field:CommandRoute.PathVariable(name = "name", nestedPath = ["name"])
    val customer: Customer
) {
    data class Customer(val id: String, val name: String)
}
