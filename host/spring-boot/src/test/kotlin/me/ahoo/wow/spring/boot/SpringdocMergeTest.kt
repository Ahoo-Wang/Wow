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

package me.ahoo.wow.spring.boot

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.SpecVersion
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.tags.Tag
import me.ahoo.test.asserts.assert
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.openapi.OpenApiSecurityScheme
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** How the Wow document joins springdoc's (decision D233 ①②): what is added, and what conflicts. */
class SpringdocMergeTest {
    @Test
    fun `an empty document should take every part of the Wow document`() {
        val target = OpenAPI(SpecVersion.V31)
        SpringdocMerge.merge(target, wow())
        target.paths.keys.assert().containsExactly("/shop/cart/{id}/put-item")
        target.components.schemas.keys.assert().containsExactly("wow.Problem")
        target.tags.map { it.name }.assert().containsExactly("shop.cart")
        target.security.assert().containsExactly(SecurityRequirement().addList("bearer"))
        target.info.title.assert().isEqualTo("shop-service")
        target.jsonSchemaDialect.assert().isEqualTo(DIALECT)
    }

    @Test
    fun `a path both have should get the Wow operations beside the application's`() {
        val target = springdoc().path("/shop/cart/{cartId}/put-item", PathItem().get(Operation().operationId("app")))
        target.path("/shop/cart/{id}/put-item", PathItem().get(Operation().operationId("same")))
        SpringdocMerge.merge(target, wow())
        target.paths["/shop/cart/{id}/put-item"]!!.let {
            it.get.operationId.assert().isEqualTo("same")
            it.post.operationId.assert().isEqualTo("put")
        }
    }

    @Test
    fun `a route both define should be a conflict, its variables compared by position`() {
        val target = springdoc().path("/shop/cart/{cartId}/put-item", PathItem().post(Operation()))
        val failure = assertThrows<IllegalStateException> { SpringdocMerge.merge(target, wow()) }
        failure.message.assert().contains("POST /shop/cart/{id}/put-item is a route of both")
    }

    @Test
    fun `a component both define should be kept once when equal, and be a conflict when not`() {
        val equal = springdoc().components(Components().addSchemas("wow.Problem", problem()))
        SpringdocMerge.merge(equal, wow())
        equal.components.schemas["wow.Problem"].assert().isEqualTo(problem())
        val other = springdoc().components(Components().addSchemas("wow.Problem", IntegerSchema()))
        assertThrows<IllegalStateException> { SpringdocMerge.merge(other, wow()) }
            .message.assert().contains("the component schemas/wow.Problem is defined differently")
    }

    @Test
    fun `the application's tags, security and info should be kept`() {
        val target = springdoc().info(Info().title("Shop").version("2"))
            .addTagsItem(Tag().name("shop.cart").description("The application's"))
            .addSecurityItem(SecurityRequirement().addList("bearer"))
        SpringdocMerge.merge(target, wow())
        target.tags.single().description.assert().isEqualTo("The application's")
        target.security.assert().hasSize(1)
        target.info.title.assert().isEqualTo("Shop")
    }

    @Test
    fun `a document of another OpenAPI version should be a conflict`() {
        val failure = assertThrows<IllegalStateException> { SpringdocMerge.merge(OpenAPI(SpecVersion.V30), wow()) }
        failure.message.assert().contains("springdoc.api-docs.version")
    }

    @Test
    fun `a Wow document without components should leave the application's untouched`() {
        val target = springdoc()
        SpringdocMerge.merge(target, wow().components(null))
        target.components.assert().isNull()
    }

    @Test
    fun `the host's security should be CoSec's bearer token for CoSec's resolver, none otherwise`() {
        WowOpenApiSecurity.of(CallerResolver.ANONYMOUS).assert().isEqualTo(WowOpenApiSecurity.NONE)
        WowOpenApiSecurity.of(DevelopmentCallerResolver()).assert().isEqualTo(WowOpenApiSecurity.DEVELOPMENT)
        WowOpenApiSecurity.DEVELOPMENT.schemes.keys.assert().containsExactly(
            DevelopmentCallerResolver.USER,
            DevelopmentCallerResolver.OWNER,
            DevelopmentCallerResolver.ROLES,
            DevelopmentCallerResolver.TENANT,
            DevelopmentCallerResolver.APP,
        )
        WowOpenApiSecurity.DEVELOPMENT.schemes.values.map { it.name }.assert()
            .isEqualTo(WowOpenApiSecurity.DEVELOPMENT.schemes.keys.toList())
        // wow-cosec's tests pin its resolver's name (CoSecCallerResolverTest).
        WowOpenApiSecurity.ofType(WowOpenApiSecurity.COSEC_CALLER_RESOLVER).assert().isEqualTo(WowOpenApiSecurity.COSEC)
        WowOpenApiSecurity.COSEC.schemes[WowOpenApiSecurity.BEARER]
            .assert().isEqualTo(OpenApiSecurityScheme.bearer("JWT", "CoSec's access token."))
        WowOpenApiSecurity.COSEC.hashCode().assert().isEqualTo(WowOpenApiSecurity.COSEC.schemes.hashCode())
        WowOpenApiSecurity.NONE.toString().assert().isEqualTo("WowOpenApiSecurity(schemes={})")
    }

    /** springdoc's document of an application without its own OpenAPI bean: its default info. */
    private fun springdoc(): OpenAPI = OpenAPI(SpecVersion.V31).info(Info().title("OpenAPI definition").version("v0"))

    private fun problem() = StringSchema().description("A problem")

    private fun wow(): OpenAPI = OpenAPI(SpecVersion.V31)
        .info(Info().title("shop-service").version("1"))
        .jsonSchemaDialect(DIALECT)
        .paths(Paths().addPathItem("/shop/cart/{id}/put-item", PathItem().post(Operation().operationId("put"))))
        .components(Components().addSchemas("wow.Problem", problem()))
        .addTagsItem(Tag().name("shop.cart"))
        .addSecurityItem(SecurityRequirement().addList("bearer"))

    private companion object {
        const val DIALECT = "https://json-schema.org/draft/2020-12/schema"
    }
}
