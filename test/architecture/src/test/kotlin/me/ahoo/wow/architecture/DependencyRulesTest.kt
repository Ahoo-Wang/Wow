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

package me.ahoo.wow.architecture

import me.ahoo.test.asserts.assert
import me.ahoo.wow.architecture.ArchitectureFixtures.writeSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Each of the [DependencyRules] catches what it forbids, in a source tree a test writes. */
class DependencyRulesTest {
    @Test
    fun `core rule should catch a Reactor import and a fully-qualified driver reference`(@TempDir root: File) {
        root.writeSource(
            ArchitectureRules.KERNEL.dir,
            "me/ahoo/wow/kernel/Bad.kt",
            """
            package me.ahoo.wow.kernel

            import reactor.core.publisher.Mono

            // org.postgresql.Driver in a comment does not count
            val client = org.postgresql.Driver()
            """.trimIndent(),
        )
        DependencyRules.CORE_IS_FRAMEWORK_FREE.violations(root).assert().containsExactly(
            "${ArchitectureRules.KERNEL.dir}/src/main/kotlin/me/ahoo/wow/kernel/Bad.kt:3: reactor.core.publisher.Mono",
            "${ArchitectureRules.KERNEL.dir}/src/main/kotlin/me/ahoo/wow/kernel/Bad.kt:6: org.postgresql.Driver",
        )
    }

    @Test
    fun `http read rule should catch the log or the kernel in the query and read packages only`(@TempDir root: File) {
        root.writeSource(
            ArchitectureRules.HTTP.dir,
            "me/ahoo/wow/http/read/Bad.kt",
            """
            package me.ahoo.wow.http.read

            import me.ahoo.wow.spi.log.EventLog

            val kernel: me.ahoo.wow.kernel.Kernel? = null
            """.trimIndent(),
        )
        root.writeSource(
            ArchitectureRules.HTTP.dir,
            "me/ahoo/wow/http/query/Bad.kt",
            """
            package me.ahoo.wow.http.query

            import me.ahoo.wow.spi.snapshot.SnapshotStore
            """.trimIndent(),
        )
        // The rest of wow-http may read them: the command routes run the kernel's gateway.
        root.writeSource(
            ArchitectureRules.HTTP.dir,
            "me/ahoo/wow/http/command/Fine.kt",
            """
            package me.ahoo.wow.http.command

            import me.ahoo.wow.spi.log.EventLog
            """.trimIndent(),
        )
        DependencyRules.HTTP_READS_ADAPT_ONLY.violations(root).assert().containsExactly(
            "${ArchitectureRules.HTTP.dir}/src/main/kotlin/me/ahoo/wow/http/query/Bad.kt:3: me.ahoo.wow.spi.snapshot.SnapshotStore",
            "${ArchitectureRules.HTTP.dir}/src/main/kotlin/me/ahoo/wow/http/read/Bad.kt:3: me.ahoo.wow.spi.log.EventLog",
            "${ArchitectureRules.HTTP.dir}/src/main/kotlin/me/ahoo/wow/http/read/Bad.kt:5: me.ahoo.wow.kernel.Kernel",
        )
    }

    @Test
    fun `api rule should catch a 9x type, Jackson or kotlinx but Flow anywhere in wow-api`(@TempDir root: File) {
        root.writeSource(
            ArchitectureRules.API.dir,
            "me/ahoo/wow/api/subscribe/Bad.kt",
            """
            package me.ahoo.wow.api.subscribe

            import me.ahoo.wow.api.messaging.Message
            import tools.jackson.databind.JsonNode
            import me.ahoo.wow.api.naming.TypeNames
            import me.ahoo.wow.api.naming.NamedBoundedContext
            """.trimIndent(),
        )
        root.writeSource(
            ArchitectureRules.API.dir,
            "me/ahoo/wow/api/annotation/Old.kt",
            """
            package me.ahoo.wow.api.annotation

            import kotlinx.coroutines.CoroutineScope
            import me.ahoo.wow.api.messaging.function.FunctionKind
            import kotlinx.coroutines.flow.Flow
            import me.ahoo.wow.api.query.ListQuery
            """.trimIndent(),
        )
        val api = "${ArchitectureRules.API.dir}/src/main/kotlin/me/ahoo/wow/api"
        DependencyRules.API_STANDS_ALONE.violations(root).assert().containsExactly(
            "$api/annotation/Old.kt:3: kotlinx.coroutines.CoroutineScope",
            "$api/annotation/Old.kt:4: me.ahoo.wow.api.messaging.function.FunctionKind",
            "$api/subscribe/Bad.kt:3: me.ahoo.wow.api.messaging.Message",
            "$api/subscribe/Bad.kt:4: tools.jackson.databind.JsonNode",
            "$api/subscribe/Bad.kt:6: me.ahoo.wow.api.naming.NamedBoundedContext",
        )
    }

    @Test
    fun `manifest and schema rules should catch a JSON library and the schema generator`(@TempDir root: File) {
        root.writeSource(
            ArchitectureRules.MANIFEST.dir,
            "me/ahoo/wow/manifest/Bad.kt",
            """
            package me.ahoo.wow.manifest

            import com.github.victools.jsonschema.generator.SchemaGenerator
            import tools.jackson.databind.JsonNode
            """.trimIndent(),
        )
        val bad = "${ArchitectureRules.MANIFEST.dir}/src/main/kotlin/me/ahoo/wow/manifest/Bad.kt"
        DependencyRules.MANIFEST_IS_PLAIN_DATA.violations(root).assert()
            .containsExactly("$bad:4: tools.jackson.databind.JsonNode")
        DependencyRules.SCHEMA_GENERATION_AT_BUILD_TIME.violations(root).assert()
            .containsExactly("$bad:3: com.github.victools.jsonschema.generator.SchemaGenerator")
    }

    @Test
    fun `query-api rule should catch a JSON library`(@TempDir root: File) {
        root.writeSource(
            ArchitectureRules.QUERY_API.dir,
            "me/ahoo/wow/api/query/Bad.kt",
            """
            package me.ahoo.wow.api.query

            import com.fasterxml.jackson.annotation.JsonProperty
            import kotlinx.serialization.Serializable
            import tools.jackson.databind.JsonNode
            import java.time.Duration
            """.trimIndent(),
        )
        val bad = "${ArchitectureRules.QUERY_API.dir}/src/main/kotlin/me/ahoo/wow/api/query/Bad.kt"
        DependencyRules.QUERY_API_IS_PLAIN_DATA.violations(root).assert().containsExactly(
            "$bad:3: com.fasterxml.jackson.annotation.JsonProperty",
            "$bad:4: kotlinx.serialization.Serializable",
            "$bad:5: tools.jackson.databind.JsonNode",
        )
    }

    @Test
    fun `test module rule should catch Testcontainers or a driver in the TCK`(@TempDir root: File) {
        root.writeSource(
            ArchitectureRules.TCK.dir,
            "me/ahoo/wow/spi/Bad.kt",
            """
            package me.ahoo.wow.spi

            import org.testcontainers.postgresql.PostgreSQLContainer

            val url = org.postgresql.Driver::class
            """.trimIndent(),
        )
        val bad = "${ArchitectureRules.TCK.dir}/src/main/kotlin/me/ahoo/wow/spi/Bad.kt"
        DependencyRules.TEST_MODULES_ARE_FRAMEWORK_FREE.violations(root).assert().containsExactly(
            "$bad:3: org.testcontainers.postgresql.PostgreSQLContainer",
            "$bad:5: org.postgresql.Driver",
        )
    }

    @Test
    fun `entry rule should catch a state admitted past the read facade`(@TempDir root: File) {
        root.writeSource(
            ArchitectureRules.HTTP.dir,
            "me/ahoo/wow/http/read/Bad.kt",
            """
            package me.ahoo.wow.http.read

            import me.ahoo.wow.query.engine.ReadFacade
            import me.ahoo.wow.query.state.StateVisibility
            """.trimIndent(),
        )
        DependencyRules.ENTRIES_READ_THROUGH_FACADE.violations(root).assert().containsExactly(
            "${ArchitectureRules.HTTP.dir}/src/main/kotlin/me/ahoo/wow/http/read/Bad.kt:4: me.ahoo.wow.query.state.StateVisibility",
        )
    }

    @Test
    fun `host rule should allow only the listed query types, a new composition among the caught`(@TempDir root: File) {
        root.writeSource(
            ArchitectureRules.SPRING_BOOT.dir,
            "me/ahoo/wow/spring/boot/Bad.kt",
            """
            package me.ahoo.wow.spring.boot

            import me.ahoo.wow.query.policy.ScopeQueryPolicy
            import me.ahoo.wow.query.engine.QueryEngine
            import me.ahoo.wow.query.store.QueryStores

            val gateway = me.ahoo.wow.query.state.SnapshotPolicyGateway::class
            val builder = QueryEngine.Builder::class
            val bound = me.ahoo.wow.query.SomeNewBulkheadComposition::class
            """.trimIndent(),
        )
        val bad = "${ArchitectureRules.SPRING_BOOT.dir}/src/main/kotlin/me/ahoo/wow/spring/boot/Bad.kt"
        DependencyRules.HOST_ASSEMBLES_THE_READ_SIDE.violations(root).assert().containsExactly(
            "$bad:3: me.ahoo.wow.query.policy.ScopeQueryPolicy",
            "$bad:7: me.ahoo.wow.query.state.SnapshotPolicyGateway",
            "$bad:9: me.ahoo.wow.query.SomeNewBulkheadComposition",
        )
    }
}
