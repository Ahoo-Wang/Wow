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

package me.ahoo.wow.rest

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** The names are the REST contract: these tests pin the values clients send and servers read. */
internal class RestContractTest {

    @Test
    fun `command headers keep their wire names`() {
        CommandHeaders.assert().isSameAs(CommandHeaders)
        CommandHeaders.TENANT_ID.assert().isEqualTo("Command-Tenant-Id")
        CommandHeaders.OWNER_ID.assert().isEqualTo("Command-Owner-Id")
        CommandHeaders.AGGREGATE_ID.assert().isEqualTo("Command-Aggregate-Id")
        CommandHeaders.AGGREGATE_VERSION.assert().isEqualTo("Command-Aggregate-Version")
        CommandHeaders.WAIT_TIME_OUT.assert().isEqualTo("Command-Wait-Timeout")
        CommandHeaders.LEGACY_WAIT_TIME_OUT.assert().isEqualTo("Command-Wait-Timout")
        CommandHeaders.WAIT_STAGE.assert().isEqualTo("Command-Wait-Stage")
        CommandHeaders.WAIT_CONTEXT.assert().isEqualTo("Command-Wait-Context")
        CommandHeaders.WAIT_PROCESSOR.assert().isEqualTo("Command-Wait-Processor")
        CommandHeaders.WAIT_FUNCTION.assert().isEqualTo("Command-Wait-Function")
        CommandHeaders.WAIT_TAIL_STAGE.assert().isEqualTo("Command-Wait-Tail-Stage")
        CommandHeaders.WAIT_TAIL_CONTEXT.assert().isEqualTo("Command-Wait-Tail-Context")
        CommandHeaders.WAIT_TAIL_PROCESSOR.assert().isEqualTo("Command-Wait-Tail-Processor")
        CommandHeaders.WAIT_TAIL_FUNCTION.assert().isEqualTo("Command-Wait-Tail-Function")
        CommandHeaders.REQUEST_ID.assert().isEqualTo("Command-Request-Id")
        CommandHeaders.LOCAL_FIRST.assert().isEqualTo("Command-Local-First")
        CommandHeaders.COMMAND_AGGREGATE_CONTEXT.assert().isEqualTo("Command-Aggregate-Context")
        CommandHeaders.COMMAND_AGGREGATE_NAME.assert().isEqualTo("Command-Aggregate-Name")
        CommandHeaders.COMMAND_TYPE.assert().isEqualTo("Command-Type")
        CommandHeaders.COMMAND_HEADER_X_PREFIX.assert().isEqualTo("Command-Header-")
    }

    @Test
    fun `wow headers keep their wire names`() {
        WowHeaders.assert().isSameAs(WowHeaders)
        WowHeaders.ERROR_CODE.assert().isEqualTo("Wow-Error-Code")
        WowHeaders.SPACE_ID.assert().isEqualTo("Wow-Space-Id")
    }

    @Test
    fun `global route paths keep their values`() {
        RoutePaths.assert().isSameAs(RoutePaths)
        RoutePaths.COMMAND_WAIT.assert().isEqualTo("/wow/command/wait")
        RoutePaths.COMMAND_SEND.assert().isEqualTo("/wow/command/send")
        RoutePaths.METADATA.assert().isEqualTo("/wow/metadata")
        RoutePaths.GLOBAL_ID.assert().isEqualTo("/wow/id/global")
        RoutePaths.BI_SCRIPT.assert().isEqualTo("/wow/bi/script")
    }

    @Test
    fun `route suffixes name their path variables`() {
        RouteVariables.assert().isSameAs(RouteVariables)
        RouteSuffixes.assert().isSameAs(RouteSuffixes)
        RouteSuffixes.SNAPSHOT_BATCH.assert().isEqualTo("snapshot/{afterId}/{limit}")
        RouteSuffixes.STATE_BATCH.assert().isEqualTo("state/{afterId}/{limit}")
        RouteSuffixes.EVENT_RANGE.assert().isEqualTo("event/{headVersion}/{tailVersion}")
        RouteSuffixes.EVENT_COMPENSATE.assert().isEqualTo("{version}/compensate")
        RouteSuffixes.STATE_VERSIONED.assert().isEqualTo("state/{version}")
        RouteSuffixes.STATE_TIME_BASED.assert().isEqualTo("state/time/{createTime}")
        RouteVariables.ID.assert().isEqualTo("id")
        RouteVariables.TENANT_ID.assert().isEqualTo("tenantId")
        RouteVariables.OWNER_ID.assert().isEqualTo("ownerId")
    }

    @Test
    fun `wow metadata names the contract types under the wow openapi context`() {
        val metadata = requireNotNull(javaClass.classLoader.getResource("META-INF/wow-metadata.json")).readText()
        metadata.assert().contains("\"alias\": \"wow.openapi\"").contains("\"me.ahoo.wow.rest\"")
    }
}
