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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The applications of the Wow modules build on their public API alone (review R2.5-7): no build or file opts in to
 * `InternalWowApi`, and a platform server's declaration does only to read a host setting no public type gives
 * (design rule K1), each on [HOST_SETTING_READERS] with its reason.
 */
class InternalOptInTest {
    private val repositoryRoot: File =
        File(requireNotNull(System.getProperty(REPOSITORY_ROOT)) { "System property $REPOSITORY_ROOT is not set." })
            .canonicalFile

    @Test
    fun `no application of the Wow modules should opt in to their internal API`() {
        ArchitectureRules.internalOptIns(repositoryRoot, HOST_SETTING_READERS).assert().isEmpty()
        ArchitectureRules.staleOptIns(repositoryRoot, HOST_SETTING_READERS).assert()
            .describedAs("Remove these entries: their declarations no longer opt in.")
            .isEmpty()
    }

    @Test
    fun `the third-party store should opt in to nothing, its tests included`() {
        SurfaceRules.spiConsumerOptIns(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `third-party opt-in rule should catch an opt-in in a test`(@TempDir root: File) {
        File(root, "test/spi-consumer/src/test/kotlin").mkdirs()
        File(root, "test/spi-consumer/src/test/kotlin/AcmeTest.kt")
            .writeText("@OptIn(InternalWowApi::class)\nclass AcmeTest\n")
        File(root, "test/spi-consumer/build/generated").mkdirs()
        File(root, "test/spi-consumer/build/generated/Generated.kt").writeText("@OptIn(Any::class)\n")
        SurfaceRules.spiConsumerOptIns(root).assert()
            .containsExactly("test/spi-consumer/src/test/kotlin/AcmeTest.kt:1: @OptIn(InternalWowApi::class)")
    }

    @Test
    fun `internal opt-in rule should excuse an allowed declaration alone`(@TempDir root: File) {
        val server = ArchitectureRules.OPS_SERVER.dir
        val path = "$server/src/main/kotlin/me/ahoo/wow/ops/server/Reads.kt"
        root.writeSource(
            server,
            "me/ahoo/wow/ops/server/Reads.kt",
            "package me.ahoo.wow.ops.server\n\nimport me.ahoo.wow.api.annotation.InternalWowApi\n\n" +
                "@Bean\n@OptIn(InternalWowApi::class)\nfun reads() = Unit\n\n@OptIn(InternalWowApi::class)\nfun other() = Unit\n",
        )
        val allowed = mapOf("$path#reads" to "a host setting", "$path#gone" to "stale")
        ArchitectureRules.internalOptIns(root, allowed).assert().containsExactly(
            "$path:3: import me.ahoo.wow.api.annotation.InternalWowApi",
            "$path:9: @OptIn(InternalWowApi::class)",
        )
        ArchitectureRules.staleOptIns(root, allowed).assert().containsExactly("$path#gone")
    }

    @Test
    fun `internal opt-in rule should catch an application's opt-in in its sources or build only`(@TempDir root: File) {
        val server = ArchitectureRules.OPS_SERVER.dir
        root.writeSource(
            server,
            "me/ahoo/wow/ops/server/Bad.kt",
            "package me.ahoo.wow.ops.server\n\n@OptIn(me.ahoo.wow.api.annotation.InternalWowApi::class)\nfun bad() = Unit\n",
        )
        File(
            root,
            "$server/build.gradle.kts"
        ).writeText("kotlin { compilerOptions { optIn.add(\"InternalWowApi\") } }\n")
        File(root, "$server/src/test/kotlin/Fixture.kt").apply { parentFile.mkdirs() }
            .writeText("@OptIn(InternalWowApi::class)\nfun fixture() = Unit\n")
        // Wow's own modules opt in through the root build.
        root.writeSource(ArchitectureRules.RUNTIME.dir, "Own.kt", "@OptIn(InternalWowApi::class)\nfun own() = Unit\n")
        ArchitectureRules.internalOptIns(root).assert().containsExactly(
            "$server/build.gradle.kts:1: kotlin { compilerOptions { optIn.add(\"InternalWowApi\") } }",
            "$server/src/main/kotlin/me/ahoo/wow/ops/server/Bad.kt:3: " +
                "@OptIn(me.ahoo.wow.api.annotation.InternalWowApi::class)",
        )
    }

    private fun File.writeSource(moduleDir: String, path: String, content: String) {
        File(this, "$moduleDir/src/main/kotlin/$path").apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }

    private companion object {
        const val REPOSITORY_ROOT = "wow-test.repository.root"
        private const val CATALOG = "platform/analytics/catalog/server/src/main/kotlin/me/ahoo/wow/analytics/catalog/server/" +
            "AnalyticsCatalogConfiguration.kt"

        /**
         * The platform servers' declarations that read a host setting no public type gives (design rule K1, one
         * reader), each with its reason; every other opt-in of an application stays forbidden (review R2.5-7).
         */
        val HOST_SETTING_READERS: Map<String, String> = mapOf(
            "$CATALOG#datasetReferenceActions" to "wow.peers.<context>.contract: no public type gives the described peers",
            "$CATALOG#manifestPeers" to "wow.peers.<context>.contract: no public type gives the described peers",
        )
    }
}
