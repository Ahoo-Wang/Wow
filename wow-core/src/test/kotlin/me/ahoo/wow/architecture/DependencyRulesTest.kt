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
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Guards the module dependency rules of the target architecture by scanning production sources.
 *
 * The Gradle `test` task of wow-core passes the repository root (`wow.repository.root`) and declares the scanned
 * source trees as inputs, so a change in any scanned module re-runs this test.
 */
class DependencyRulesTest {
    private val repositoryRoot: File =
        File(requireNotNull(System.getProperty(REPOSITORY_ROOT)) { "System property $REPOSITORY_ROOT is not set." })
            .canonicalFile

    @Test
    fun `every rule should scan existing sources`() {
        DependencyRules.ALL.flatMap { it.modules }.distinct().forEach { module ->
            File(repositoryRoot, "$module/src/main").isDirectory.assert()
                .describedAs("$module/src/main must exist under $repositoryRoot")
                .isTrue()
        }
    }

    @TestFactory
    fun `production sources should follow the dependency rules`(): List<DynamicTest> =
        DependencyRules.ALL.map { rule ->
            DynamicTest.dynamicTest(rule.name) {
                rule.violations(repositoryRoot).assert()
                    .describedAs("Rule violated: ${rule.name}")
                    .isEmpty()
            }
        }

    @Test
    fun `core rule should catch an HTTP import and a fully-qualified driver reference`(@TempDir root: File) {
        root.writeSource(
            "wow-core",
            "me/ahoo/wow/Bad.kt",
            """
            package me.ahoo.wow

            import org.springframework.http.HttpStatus
            // import com.mongodb.client.MongoClient is a comment and does not count

            class Bad(val client: com.mongodb.reactivestreams.client.MongoClient)
            """.trimIndent(),
        )

        DependencyRules.CORE_HAS_NO_HTTP_OR_DRIVERS.violations(root).assert().containsExactly(
            "wow-core/src/main/kotlin/me/ahoo/wow/Bad.kt:3: org.springframework.http.HttpStatus",
            "wow-core/src/main/kotlin/me/ahoo/wow/Bad.kt:6: com.mongodb.reactivestreams.client.MongoClient",
        )
    }

    @Test
    fun `query rule should catch a webflux import`(@TempDir root: File) {
        root.writeSource(
            "wow-query",
            "me/ahoo/wow/query/Bad.kt",
            """
            package me.ahoo.wow.query

            import org.springframework.web.reactive.function.server.ServerRequest
            """.trimIndent(),
        )

        DependencyRules.QUERY_CORE_HAS_NO_HTTP_OR_DRIVERS.violations(root).assert().containsExactly(
            "wow-query/src/main/kotlin/me/ahoo/wow/query/Bad.kt:3: " +
                "org.springframework.web.reactive.function.server.ServerRequest",
        )
    }

    @Test
    fun `backend rule should catch a cross-backend import`(@TempDir root: File) {
        root.writeSource(
            "wow-redis",
            "me/ahoo/wow/redis/Bad.kt",
            """
            package me.ahoo.wow.redis

            import me.ahoo.wow.mongo.MongoEventStore
            import org.bson.Document
            """.trimIndent(),
        )

        val redisRule = DependencyRules.BACKENDS_ARE_INDEPENDENT.single { it.modules == listOf("wow-redis") }
        redisRule.violations(root).assert().containsExactly(
            "wow-redis/src/main/kotlin/me/ahoo/wow/redis/Bad.kt:3: me.ahoo.wow.mongo.MongoEventStore",
            "wow-redis/src/main/kotlin/me/ahoo/wow/redis/Bad.kt:4: org.bson.Document",
        )
    }

    @Test
    fun `store rule should catch a query import outside the query package only`(@TempDir root: File) {
        root.writeSource(
            "wow-elasticsearch",
            "me/ahoo/wow/elasticsearch/eventsourcing/Bad.kt",
            """
            package me.ahoo.wow.elasticsearch.eventsourcing

            import me.ahoo.wow.api.query.MaterializedSnapshot
            import me.ahoo.wow.query.QueryBackend
            import me.ahoo.wow.elasticsearch.query.ElasticsearchQueryBackend
            """.trimIndent(),
        )
        root.writeSource(
            "wow-elasticsearch",
            "me/ahoo/wow/elasticsearch/query/Allowed.kt",
            """
            package me.ahoo.wow.elasticsearch.query

            import me.ahoo.wow.query.QueryBackend
            """.trimIndent(),
        )

        val esRule = DependencyRules.STORES_HAVE_NO_QUERY.single { it.modules == listOf("wow-elasticsearch") }
        esRule.violations(root).assert().containsExactly(
            "wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/eventsourcing/Bad.kt:4: " +
                "me.ahoo.wow.query.QueryBackend",
            "wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/eventsourcing/Bad.kt:5: " +
                "me.ahoo.wow.elasticsearch.query.ElasticsearchQueryBackend",
        )
    }

    private fun File.writeSource(module: String, path: String, content: String) {
        val file = File(this, "$module/src/main/kotlin/$path")
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private companion object {
        const val REPOSITORY_ROOT = "wow.repository.root"
    }
}
