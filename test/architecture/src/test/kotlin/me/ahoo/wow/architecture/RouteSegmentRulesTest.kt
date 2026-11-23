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

/** Wow's own routes hold no data in their paths but an aggregate's id ([RouteSegmentRules]). */
class RouteSegmentRulesTest {
    private val repositoryRoot: File =
        File(requireNotNull(System.getProperty(REPOSITORY_ROOT)) { "System property $REPOSITORY_ROOT is not set." })

    @Test
    fun `wow-http's routes should declare no path variable but an aggregate id and the Manifest's names`() {
        RouteSegmentRules.placeholders(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `the serving modules should read no path variable but those, or an application's declared ones`() {
        RouteSegmentRules.reads(repositoryRoot).assert().isEmpty()
    }

    @Test
    fun `each listed read of an application's declared path variables should still be there`() {
        val found = RouteSegmentRules.reads(repositoryRoot, listedToo = true)
            .map { it.substringBefore(":") to it.substringAfterLast(": ") }.toSet()
        (RouteSegmentRules.DECLARED.keys - found).assert().describedAs("Listed reads no longer there").isEmpty()
    }

    @Test
    fun `a tenant, an id or a subscription in a route of wow-http should be caught, the aggregate id and a comment not`(
        @TempDir root: File,
    ) {
        // The operator's routes of 10.0.0-beta.16, which put an entry's subscription, tenant and id in their path.
        File(root, "${ArchitectureRules.HTTP.dir}/src/main/kotlin/me/ahoo/wow/http/ops/Parked.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                fun parkedRoutes() = coRouter {
                    // GET("{${'$'}TENANT}") in a comment
                    val entry = "${'$'}{OpsPaths.PARKED}/{${'$'}SUBSCRIPTION}/{${'$'}CONTEXT}/{${'$'}AGGREGATE}/{${'$'}TENANT}/{${'$'}ID}"
                    GET(entry) { request -> handler.get(request.pathVariable(TENANT), request.pathVariable(ID)) }
                    GET("/wow/owners/{ownerId}/{id}") { request -> handler.owner(request.pathVariable("ownerId")) }
                }
                """.trimIndent(),
            )
        }
        RouteSegmentRules.placeholders(root).assert().containsExactly(
            "ops/Parked.kt:3: {SUBSCRIPTION}",
            "ops/Parked.kt:3: {CONTEXT}",
            "ops/Parked.kt:3: {TENANT}",
            "ops/Parked.kt:5: {ownerId}",
        )
        RouteSegmentRules.reads(root).assert().containsExactly(
            "ops/Parked.kt:4: TENANT",
            "ops/Parked.kt:5: \"ownerId\"",
        )
    }

    private companion object {
        const val REPOSITORY_ROOT = "wow-test.repository.root"
    }
}
