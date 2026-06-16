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

package me.ahoo.wow.openapi.snapshot

import io.swagger.v3.core.util.ObjectMapperFactory
import io.swagger.v3.oas.models.OpenAPI
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.openapi.snapshot.OpenApiSnapshotSupport.assertContractSnapshot
import me.ahoo.wow.openapi.snapshot.OpenApiSnapshotSupport.assertOpenApiSnapshot
import me.ahoo.wow.openapi.snapshot.OpenApiSnapshotSupport.resourcePath
import org.junit.jupiter.api.Test

internal class OpenApiCompatibilitySnapshotTest {
    private val mapper = ObjectMapperFactory.createJson()
    private val currentContext = MaterializedNamedBoundedContext("example-service")

    @Test
    fun `generated openapi should match example domain compatibility snapshot`() {
        val openAPI = OpenAPI()
        RouterSpecs(currentContext).build().mergeOpenAPI(openAPI)

        assertOpenApiSnapshot(
            openAPI = openAPI,
            snapshotPath = resourcePath("openapi/example-domain-openapi.snapshot.json")
        )
    }

    @Test
    fun `generated route contracts should match example domain compatibility snapshot`() {
        val routerSpecs = RouterSpecs(currentContext).build()
        val routeShape = routerSpecs.map { route ->
            mapOf(
                "id" to route.id,
                "path" to route.path,
                "method" to route.method,
                "accept" to route.accept,
                "parameterNames" to route.parameters.map {
                    "${it.`in`}:${it.name}:${it.required}"
                },
                "requestBody" to (route.requestBody != null),
                "responseCodes" to route.responses.keys.sorted(),
                "tagNames" to route.tags.map { it.name }.sorted()
            )
        }.sortedWith(
            compareBy(
                { it["path"].toString() },
                { it["method"].toString() },
                { it["id"].toString() }
            )
        )

        assertContractSnapshot(
            contractJson = mapper.writeValueAsString(routeShape),
            snapshotPath = resourcePath("openapi/example-domain-contract.snapshot.json")
        )
    }
}
