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

package me.ahoo.wow.bi

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class BiScriptOptionsTest {
    @Test
    fun `should use the designed defaults`() {
        val options = BiScriptOptions()

        options.database.assert().isEqualTo("bi_db")
        options.consumerDatabase.assert().isEqualTo("bi_db_consumer")
        options.cluster.assert().isEqualTo("{cluster}")
        options.installation.assert().isEqualTo("{installation}")
        options.shard.assert().isEqualTo("{shard}")
        options.replica.assert().isEqualTo("{replica}")
        options.timezone.assert().isEqualTo("Asia/Shanghai")
        options.kafkaBootstrapServers.assert().isEqualTo("localhost:9093")
        options.topicPrefix.assert().isEqualTo("wow.")
        options.maxExpansionDepth.assert().isEqualTo(5)
        options.unsupportedTypeStrategy.assert().isEqualTo(UnsupportedTypeStrategy.FAIL)
        options.objectMapStrategy.assert().isEqualTo(ObjectMapStrategy.STRING_VALUE_WITH_DIAGNOSTIC)
        options.validate().assert().isSameAs(options)
    }

    @Test
    fun `should reject blank required values`() {
        listOf(
            BiScriptOptions(database = " "),
            BiScriptOptions(consumerDatabase = " "),
            BiScriptOptions(cluster = " "),
            BiScriptOptions(installation = " "),
            BiScriptOptions(shard = " "),
            BiScriptOptions(replica = " "),
            BiScriptOptions(timezone = " "),
            BiScriptOptions(kafkaBootstrapServers = " "),
            BiScriptOptions(topicPrefix = " "),
        ).forEach { options ->
            options.runCatching { validate() }.isFailure.assert().isTrue()
        }
    }

    @Test
    fun `should reject control characters in required values`() {
        listOf(
            BiScriptOptions(database = "bi\u0000db"),
            BiScriptOptions(consumerDatabase = "bi\ndb"),
            BiScriptOptions(cluster = "cluster\tname"),
            BiScriptOptions(installation = "installation\rname"),
            BiScriptOptions(shard = "shard\bname"),
            BiScriptOptions(replica = "replica\u007Fname"),
            BiScriptOptions(timezone = "Asia\nShanghai"),
            BiScriptOptions(kafkaBootstrapServers = "localhost\n9093"),
            BiScriptOptions(topicPrefix = "wow.\u0000"),
        ).forEach { options ->
            options.runCatching { validate() }.isFailure.assert().isTrue()
        }
    }

    @Test
    fun `should reject expansion depth below one`() {
        BiScriptOptions(maxExpansionDepth = 0)
            .runCatching { validate() }
            .isFailure
            .assert()
            .isTrue()
    }

    @Test
    fun `should expose structured diagnostics`() {
        val diagnostic = BiScriptDiagnostic(
            code = BiScriptDiagnosticCode.MAX_DEPTH_REACHED,
            severity = BiScriptDiagnostic.Severity.WARNING,
            aggregate = "example.order",
            path = "items.product",
            message = "Max expansion depth reached.",
        )

        val result = BiScriptResult(
            script = "SELECT 1",
            diagnostics = listOf(diagnostic),
        )

        result.script.assert().isEqualTo("SELECT 1")
        result.diagnostics.assert().containsExactly(diagnostic)
        BiScriptDiagnosticCode.entries.assert().containsExactly(
            BiScriptDiagnosticCode.OBJECT_MAP_FALLBACK,
            BiScriptDiagnosticCode.UNSUPPORTED_TYPE_FALLBACK,
            BiScriptDiagnosticCode.MAX_DEPTH_REACHED,
        )
    }
}
