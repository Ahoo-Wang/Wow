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

package me.ahoo.wow.viewstore.starter

import io.swagger.v3.core.util.Json31
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Schema
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.modeling.getContextAliasPrefix
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.spring.boot.starter.WowAutoConfiguration.Companion.WOW_CURRENT_BOUNDED_CONTEXT
import org.junit.jupiter.api.Test
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.lang.reflect.Type
import java.util.concurrent.atomic.AtomicInteger

/** The view store's own routes are documented from schemas generated once, at startup. */
class ViewStoreOpenApiTest {
    private val hostContext = MaterializedNamedBoundedContext("example-service")
    private val paths = ViewStorePaths(hostContext)
    private val hostPrefix = hostContext.getContextAliasPrefix()

    private val contexts = AtomicInteger()
    private val generatedSchemas = AtomicInteger()

    /** A context that counts the schemas it generates. */
    private val countingContext: (String) -> OpenAPIComponentContext = { prefix ->
        contexts.incrementAndGet()
        val delegate = OpenAPIComponentContext.default(defaultSchemaNamePrefix = prefix)
        object : OpenAPIComponentContext by delegate {
            override fun schema(mainTargetType: Type, vararg typeParameters: Type): Schema<*> {
                generatedSchemas.incrementAndGet()
                return delegate.schema(mainTargetType, *typeParameters)
            }
        }
    }

    private fun OpenAPI.json(): String = Json31.mapper().writeValueAsString(this)

    @Test
    fun `generates the schemas once and merges equal documents after`() {
        val openApi = ViewStoreOpenApi(paths, hostPrefix, countingContext).render()
        val generated = generatedSchemas.get()
        generated.assert().isGreaterThan(0)

        val first = OpenAPI().also(openApi::merge)
        val second = OpenAPI().also(openApi::merge)
        repeat(3) { openApi.merge(OpenAPI()) }

        contexts.get().assert().isEqualTo(1)
        generatedSchemas.get().assert().isEqualTo(generated)
        second.json().assert().isEqualTo(first.json())
        first.paths.keys.assert().containsExactly(
            paths.systemViews,
            paths.systemView,
            paths.preferences,
            paths.replay,
            paths.claim,
        )
        first.components.schemas.keys.filterNot { it.contains('.') }.assert().isEmpty()
    }

    @Test
    fun `each document gets its own path items and operations`() {
        val openApi = ViewStoreOpenApi(paths, hostPrefix).render()
        val first = OpenAPI().also(openApi::merge)
        val second = OpenAPI().also(openApi::merge)
        val expected = second.json()

        first.paths[paths.preferences]!!.put.summary = "changed"
        first.paths[paths.preferences]!!.put.parameters.clear()
        first.paths[paths.replay]!!.get.responses.remove("204")

        second.json().assert().isEqualTo(expected)
        OpenAPI().also(openApi::merge).json().assert().isEqualTo(expected)
    }

    @Test
    fun `merges on a non-blocking thread once rendered`() {
        val openApi = ViewStoreOpenApi(paths, hostPrefix, countingContext).render()
        val generated = generatedSchemas.get()
        val expected = OpenAPI().also(openApi::merge).json()

        val merged = Mono.fromCallable {
            Schedulers.isInNonBlockingThread().assert().isTrue()
            OpenAPI().also(openApi::merge)
        }.subscribeOn(Schedulers.parallel()).block()!!

        merged.json().assert().isEqualTo(expected)
        generatedSchemas.get().assert().isEqualTo(generated)
    }

    @Test
    fun `refuses to generate the schemas on a non-blocking thread`() {
        val openApi = ViewStoreOpenApi(paths, hostPrefix, countingContext)
        val failure = Mono.fromCallable { openApi.merge(OpenAPI()) }
            .subscribeOn(Schedulers.parallel())
            .materialize()
            .block()!!
            .throwable
        failure.assert().isInstanceOf(IllegalStateException::class.java)
        contexts.get().assert().isZero()

        // A blocking thread may still generate them on first use.
        val merged = OpenAPI().also(openApi::merge)
        merged.paths.keys.assert().contains(paths.preferences)
        contexts.get().assert().isEqualTo(1)
    }

    private val customizerRunner = ApplicationContextRunner()
        .withUserConfiguration(ViewStoreAutoConfiguration.ViewStoreOpenApiConfiguration::class.java)
        .withBean(WOW_CURRENT_BOUNDED_CONTEXT, NamedBoundedContext::class.java, { hostContext })
        .withBean(ViewStorePaths::class.java, { paths })
        .withBean(ViewStoreRouteGuard::class.java, {
            ViewStoreRouteGuard(paths, RouterSpecs(hostContext).build(), emptySet())
        })

    @Test
    fun `creates the customizer at startup under lazy initialization`() {
        customizerRunner
            .withPropertyValues("spring.main.lazy-initialization=true")
            .withInitializer { it.addBeanFactoryPostProcessor(LazyInitializationBeanFactoryPostProcessor()) }
            .run { context ->
                context.beanFactory.getBeanDefinition(CUSTOMIZER).isLazyInit.assert().isFalse()
                context.beanFactory.containsSingleton(CUSTOMIZER).assert().isTrue()
            }
    }

    @Test
    fun `documents nothing when Wow's OpenAPI is disabled`() {
        customizerRunner
            .withPropertyValues("wow.openapi.enabled=false")
            .run { context ->
                context.containsBean(CUSTOMIZER).assert().isFalse()
            }
    }

    private companion object {
        const val CUSTOMIZER = "viewStoreOpenApiCustomizer"
    }
}
