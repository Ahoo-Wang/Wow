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

package me.ahoo.wow.rest.bi

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

internal class BiScriptContractTest {

    @Test
    fun `request defaults to a deploy with server options`() {
        val request = BiScriptRequest()
        request.operation.assert().isEqualTo(BiScriptOperationMode.DEPLOY)
        request.database.assert().isNull()
        request.consumerDatabase.assert().isNull()
        request.topology.assert().isNull()
        request.timezone.assert().isNull()
        request.kafkaBootstrapServers.assert().isNull()
        request.topicPrefix.assert().isNull()
        request.maxExpansionDepth.assert().isNull()
        request.unsupportedTypeStrategy.assert().isNull()
        request.replayFromEarliestConfirmed.assert().isNull()
    }

    @Test
    fun `topology request carries an optional cluster`() {
        val standalone = BiScriptTopologyRequest(BiScriptTopologyMode.STANDALONE)
        standalone.cluster.assert().isNull()
        val cluster = BiScriptTopologyRequest(BiScriptTopologyMode.CLUSTER, BiScriptClusterRequest("bi", "main"))
        cluster.mode.assert().isEqualTo(BiScriptTopologyMode.CLUSTER)
        cluster.cluster.assert().isEqualTo(BiScriptClusterRequest(name = "bi", installation = "main"))
        BiScriptClusterRequest().name.assert().isNull()
        BiScriptClusterRequest().installation.assert().isNull()
    }

    @Test
    fun `response carries the script and its diagnostics`() {
        val diagnostic = BiScriptDiagnosticResponse(
            code = "UNSUPPORTED_TYPE",
            aggregate = "order",
            path = "items",
            sourceType = "java.lang.Object",
            decision = "RAW_JSON",
            message = "kept as raw JSON",
        )
        val response = BiScriptResponse(script = "SELECT 1;", destructive = false, diagnostics = listOf(diagnostic))
        response.script.assert().isEqualTo("SELECT 1;")
        response.destructive.assert().isFalse()
        response.diagnostics.assert().containsExactly(diagnostic)
        diagnostic.code.assert().isEqualTo("UNSUPPORTED_TYPE")
        diagnostic.aggregate.assert().isEqualTo("order")
        diagnostic.path.assert().isEqualTo("items")
        diagnostic.sourceType.assert().isEqualTo("java.lang.Object")
        diagnostic.decision.assert().isEqualTo("RAW_JSON")
        diagnostic.message.assert().isEqualTo("kept as raw JSON")
    }

    @Test
    fun `enums keep their wire values`() {
        BiScriptOperationMode.entries.map { it.name }.assert().containsExactly("DEPLOY", "RESET")
        BiScriptTopologyMode.entries.map { it.name }.assert().containsExactly("CLUSTER", "STANDALONE")
        BiScriptUnsupportedTypeStrategy.entries.map { it.name }.assert().containsExactly("FAIL", "RAW_JSON")
    }

    @Test
    fun `diagnostic count header keeps its wire name`() {
        BiScriptHeaders.assert().isSameAs(BiScriptHeaders)
        BiScriptHeaders.DIAGNOSTIC_COUNT.assert().isEqualTo("Wow-BI-Diagnostic-Count")
    }
}
