# wow-webflux Runtime Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor `wow-webflux` into a clear REST runtime contract layer that materializes `HttpRouteContract` into WebFlux routes without changing RESTful API behavior.

**Architecture:** Keep `wow-openapi` as the source of route contracts and keep `wow-webflux` as the runtime materializer. Split route materialization, predicate creation, handler factory lookup, and metadata-specific factory support into focused types. `wow-spring-boot-starter` remains an assembly layer only.

**Tech Stack:** Kotlin 2.3.20, Spring WebFlux functional routing, Reactor, JUnit Jupiter, FluentAssert, Gradle.

---

## File Structure

- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/HttpRoutePredicateFactory.kt`
  - Owns conversion from `HttpRouteContract` to Spring `RequestPredicate`.
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/HttpRouteMaterializer.kt`
  - Owns single-route conversion from `HttpRouteContract` to `(RequestPredicate, HandlerFunction<ServerResponse>)`.
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionRegistrar.kt`
  - Adds `requireHttpFactory(contract)` and keeps registry/override behavior centralized.
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouterFunctionBuilder.kt`
  - Builds router functions by delegating single-route materialization.
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionFactorySupport.kt`
  - Provides aggregate, command, and no-metadata factory support base classes.
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/*QueryHandlerFunction.kt`
  - Migrates base query factories to aggregate metadata support.
- Modify: direct aggregate factories under `route/event`, `route/snapshot`, and `route/state`
  - Removes repeated `requireAggregateHandlerMetadata` calls.
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/command/CommandHandlerFunction.kt`
  - Migrates command route factory to command metadata support.
- Modify: global/no-metadata factories under `route/command`, `route/global`, and `wait`
  - Removes unused `contract` and `metadata` override boilerplate.
- Test: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/HttpRoutePredicateFactoryTest.kt`
- Test: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/HttpRouteMaterializerTest.kt`
- Test: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionFactorySupportTest.kt`
- Modify tests:
  - `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionRegistrarContractTest.kt`
  - `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/RouterFunctionBuilderTest.kt`
  - `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfigurationTest.kt`

---

### Task 1: Lock route materialization behavior with tests

**Files:**
- Create: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/HttpRoutePredicateFactoryTest.kt`
- Create: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/HttpRouteMaterializerTest.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionRegistrarContractTest.kt`

- [ ] **Step 1: Write failing predicate factory tests**

Create `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/HttpRoutePredicateFactoryTest.kt`:

```kotlin
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

package me.ahoo.wow.webflux.route

import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerRequest

class HttpRoutePredicateFactoryTest {

    private val factory = HttpRoutePredicateFactory()

    @Test
    fun `should create predicate matching method path and accept`() {
        val contract = routeContract(method = "POST", path = "/test/{id}", accept = MediaType.APPLICATION_JSON_VALUE)
        val predicate = factory.create(contract)
        val request = serverRequest(
            MockServerHttpRequest
                .post("/test/aggregate-id")
                .accept(MediaType.APPLICATION_JSON)
        )

        predicate.test(request).assert().isTrue()
    }

    @Test
    fun `should reject request with different method`() {
        val contract = routeContract(method = "POST", path = "/test/{id}", accept = MediaType.APPLICATION_JSON_VALUE)
        val predicate = factory.create(contract)
        val request = serverRequest(
            MockServerHttpRequest
                .get("/test/aggregate-id")
                .accept(MediaType.APPLICATION_JSON)
        )

        predicate.test(request).assert().isFalse()
    }

    @Test
    fun `should reject request with different accept`() {
        val contract = routeContract(method = "GET", path = "/test/{id}", accept = MediaType.APPLICATION_JSON_VALUE)
        val predicate = factory.create(contract)
        val request = serverRequest(
            MockServerHttpRequest
                .get("/test/aggregate-id")
                .accept(MediaType.TEXT_PLAIN)
        )

        predicate.test(request).assert().isFalse()
    }

    private fun routeContract(
        method: String,
        path: String,
        accept: String
    ): HttpRouteContract {
        return HttpRouteContract(
            routeId = "test.route",
            method = method,
            path = path,
            accept = accept,
            handlerKey = "handler.key",
            handlerMetadata = HttpRouteHandlerMetadata.None
        )
    }

    private fun serverRequest(requestBuilder: MockServerHttpRequest.BaseBuilder<*>): ServerRequest {
        val exchange = MockServerWebExchange.from(requestBuilder)
        return ServerRequest.create(exchange, HandlerStrategies.withDefaults().messageReaders())
    }
}
```

- [ ] **Step 2: Write failing materializer tests**

Create `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/HttpRouteMaterializerTest.kt`:

```kotlin
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

package me.ahoo.wow.webflux.route

import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerResponse

class HttpRouteMaterializerTest {

    @Test
    fun `should materialize predicate and handler from contract`() {
        val contract = routeContract()
        val factory = CapturingHttpRouteHandlerFunctionFactory("handler.key")
        val materializer = HttpRouteMaterializer(
            routeHandlerFunctionRegistrar = RouteHandlerFunctionRegistrar(listOf(factory))
        )

        val binding = materializer.materialize(contract)

        binding.predicate.assert().isNotNull()
        binding.handlerFunction.assert().isSameAs(factory.handlerFunction)
        factory.createdContract.assert().isSameAs(contract)
        factory.createdMetadata.assert().isSameAs(contract.handlerMetadata)
    }

    private fun routeContract(): HttpRouteContract {
        return HttpRouteContract(
            routeId = "test.route",
            method = "GET",
            path = "/test",
            handlerKey = "handler.key",
            handlerMetadata = HttpRouteHandlerMetadata.None
        )
    }
}

private class CapturingHttpRouteHandlerFunctionFactory(
    override val handlerKey: String
) : HttpRouteHandlerFunctionFactory {
    val handlerFunction = HandlerFunction<ServerResponse> {
        ServerResponse.ok().build()
    }
    lateinit var createdContract: HttpRouteContract
    lateinit var createdMetadata: HttpRouteHandlerMetadata

    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata
    ): HandlerFunction<ServerResponse> {
        createdContract = contract
        createdMetadata = metadata
        return handlerFunction
    }
}
```

- [ ] **Step 3: Add registrar required lookup test**

Append this test to `RouteHandlerFunctionRegistrarContractTest` and add `import org.junit.jupiter.api.assertThrows`:

```kotlin
    @Test
    fun `should require http factory with route diagnostics`() {
        val contract = HttpRouteContract(
            routeId = "route",
            method = "POST",
            path = "/route/{id}",
            handlerKey = "missing.handler",
            handlerMetadata = HttpRouteHandlerMetadata.None
        )
        val registrar = RouteHandlerFunctionRegistrar()

        val error = assertThrows<IllegalArgumentException> {
            registrar.requireHttpFactory(contract)
        }

        error.message.assert().isEqualTo(
            "HttpRouteHandlerFunctionFactory not found - " +
                "handlerKey:[${contract.handlerKey}], " +
                "method:[${contract.method}], " +
                "path:[${contract.path}], " +
                "routeId:[${contract.routeId}]."
        )
    }
```

- [ ] **Step 4: Run tests and verify they fail**

Run:

```bash
./gradlew :wow-webflux:test --tests "me.ahoo.wow.webflux.route.HttpRoutePredicateFactoryTest" --tests "me.ahoo.wow.webflux.route.HttpRouteMaterializerTest" --tests "me.ahoo.wow.webflux.route.RouteHandlerFunctionRegistrarContractTest"
```

Expected: FAIL because `HttpRoutePredicateFactory`, `HttpRouteMaterializer`, `HttpRouteBinding`, and `RouteHandlerFunctionRegistrar.requireHttpFactory` do not exist.

---

### Task 2: Implement route materialization layer

**Files:**
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/HttpRoutePredicateFactory.kt`
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/HttpRouteMaterializer.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionRegistrar.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouterFunctionBuilder.kt`
- Test: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/RouterFunctionBuilderTest.kt`

- [ ] **Step 1: Implement predicate factory**

Create `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/HttpRoutePredicateFactory.kt`:

```kotlin
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

package me.ahoo.wow.webflux.route

import me.ahoo.wow.openapi.contract.HttpRouteContract
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RequestPredicate
import org.springframework.web.reactive.function.server.RequestPredicates

class HttpRoutePredicateFactory {
    fun create(contract: HttpRouteContract): RequestPredicate {
        val acceptMediaTypes = MediaType.parseMediaTypes(contract.accept).toTypedArray()
        val httpMethod = HttpMethod.valueOf(contract.method)
        return RequestPredicates.path(contract.path)
            .and(RequestPredicates.method(httpMethod))
            .and(RequestPredicates.accept(*acceptMediaTypes))
    }
}
```

- [ ] **Step 2: Implement materializer**

Create `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/HttpRouteMaterializer.kt`:

```kotlin
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

package me.ahoo.wow.webflux.route

import me.ahoo.wow.openapi.contract.HttpRouteContract
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.RequestPredicate
import org.springframework.web.reactive.function.server.ServerResponse

data class HttpRouteBinding(
    val predicate: RequestPredicate,
    val handlerFunction: HandlerFunction<ServerResponse>
)

class HttpRouteMaterializer(
    private val routeHandlerFunctionRegistrar: RouteHandlerFunctionRegistrar,
    private val predicateFactory: HttpRoutePredicateFactory = HttpRoutePredicateFactory()
) {
    fun materialize(contract: HttpRouteContract): HttpRouteBinding {
        val factory = routeHandlerFunctionRegistrar.requireHttpFactory(contract)
        return HttpRouteBinding(
            predicate = predicateFactory.create(contract),
            handlerFunction = factory.create(contract)
        )
    }
}
```

- [ ] **Step 3: Add required factory lookup**

Modify `RouteHandlerFunctionRegistrar.kt` to add this method below `getHttpFactory`:

```kotlin
    fun requireHttpFactory(contract: HttpRouteContract): HttpRouteHandlerFunctionFactory {
        return getHttpFactory(contract.handlerKey)
            ?: throw IllegalArgumentException(
                "HttpRouteHandlerFunctionFactory not found - " +
                    "handlerKey:[${contract.handlerKey}], " +
                    "method:[${contract.method}], " +
                    "path:[${contract.path}], " +
                    "routeId:[${contract.routeId}]."
            )
    }
```

Also add this import:

```kotlin
import me.ahoo.wow.openapi.contract.HttpRouteContract
```

- [ ] **Step 4: Refactor router builder to delegate materialization**

Replace `RouterFunctionBuilder.kt` with this implementation:

```kotlin
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

package me.ahoo.wow.webflux.route

import me.ahoo.wow.openapi.RouterSpecs
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse

/**
 * [org.springframework.web.reactive.function.server.support.RouterFunctionMapping]
 */
@Suppress("LongParameterList")
class RouterFunctionBuilder(
    private val routerSpecs: RouterSpecs,
    routeHandlerFunctionRegistrar: RouteHandlerFunctionRegistrar,
    private val routeMaterializer: HttpRouteMaterializer = HttpRouteMaterializer(routeHandlerFunctionRegistrar)
) {

    fun build(): RouterFunction<ServerResponse> {
        val routerFunctionBuilder = RouterFunctions.route()
        for (contract in routerSpecs.toRouteCatalog().routes) {
            val binding = routeMaterializer.materialize(contract)
            routerFunctionBuilder.route(
                binding.predicate,
                binding.handlerFunction
            )
        }
        return routerFunctionBuilder.build()
    }
}
```

- [ ] **Step 5: Run route materialization tests**

Run:

```bash
./gradlew :wow-webflux:test --tests "me.ahoo.wow.webflux.route.HttpRoutePredicateFactoryTest" --tests "me.ahoo.wow.webflux.route.HttpRouteMaterializerTest" --tests "me.ahoo.wow.webflux.route.RouterFunctionBuilderTest" --tests "me.ahoo.wow.webflux.route.RouteHandlerFunctionRegistrarContractTest"
```

Expected: PASS.

- [ ] **Step 6: Commit materialization layer**

```bash
git add wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/HttpRoutePredicateFactory.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/HttpRouteMaterializer.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionRegistrar.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouterFunctionBuilder.kt \
  wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/HttpRoutePredicateFactoryTest.kt \
  wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/HttpRouteMaterializerTest.kt \
  wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionRegistrarContractTest.kt
git commit -m "refactor(webflux): isolate route materialization"
```

---

### Task 3: Add handler factory support tests and implementation

**Files:**
- Create: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionFactorySupportTest.kt`
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionFactorySupport.kt`

- [ ] **Step 1: Write failing support tests**

Create `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionFactorySupportTest.kt`:

```kotlin
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

package me.ahoo.wow.webflux.route

import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.openapi.metadata.commandRouteMetadata
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerResponse

class RouteHandlerFunctionFactorySupportTest {

    private val aggregateRouteMetadata =
        MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata()

    @Test
    fun `aggregate support should pass aggregate metadata to typed creator`() {
        val factory = TestAggregateFactory("aggregate.handler")
        val metadata = HttpRouteHandlerMetadata.Aggregate(aggregateRouteMetadata)
        val contract = routeContract(factory.handlerKey, metadata)

        factory.create(contract)

        factory.createdMetadata.assert().isSameAs(metadata)
    }

    @Test
    fun `aggregate support should reject non aggregate metadata`() {
        val factory = TestAggregateFactory("aggregate.handler")
        val contract = routeContract(factory.handlerKey, HttpRouteHandlerMetadata.None)

        val error = assertThrows<IllegalStateException> {
            factory.create(contract)
        }

        error.message.assert().contains("handlerKey:[aggregate.handler]")
        error.message.assert().contains(HttpRouteHandlerMetadata.Aggregate::class.java.name)
    }

    @Test
    fun `command support should pass command metadata to typed creator`() {
        val factory = TestCommandFactory("command.handler")
        val metadata = HttpRouteHandlerMetadata.Command(
            aggregateRouteMetadata = aggregateRouteMetadata,
            commandRouteMetadata = MockCreateAggregate::class.java.commandRouteMetadata()
        )
        val contract = routeContract(factory.handlerKey, metadata)

        factory.create(contract)

        factory.createdMetadata.assert().isSameAs(metadata)
    }

    @Test
    fun `command support should reject non command metadata`() {
        val factory = TestCommandFactory("command.handler")
        val contract = routeContract(factory.handlerKey, HttpRouteHandlerMetadata.Aggregate(aggregateRouteMetadata))

        val error = assertThrows<IllegalStateException> {
            factory.create(contract)
        }

        error.message.assert().contains("handlerKey:[command.handler]")
        error.message.assert().contains(HttpRouteHandlerMetadata.Command::class.java.name)
    }

    @Test
    fun `no metadata support should ignore metadata shape`() {
        val factory = TestNoMetadataFactory("global.handler")
        val contract = routeContract(factory.handlerKey, HttpRouteHandlerMetadata.Aggregate(aggregateRouteMetadata))

        factory.create(contract)

        factory.createdContract.assert().isSameAs(contract)
    }

    private fun routeContract(
        handlerKey: String,
        metadata: HttpRouteHandlerMetadata
    ): HttpRouteContract {
        return HttpRouteContract(
            routeId = "test.route",
            method = "GET",
            path = "/test",
            handlerKey = handlerKey,
            handlerMetadata = metadata
        )
    }
}

private class TestAggregateFactory(
    handlerKey: String
) : AggregateRouteHandlerFunctionFactorySupport(handlerKey) {
    lateinit var createdMetadata: HttpRouteHandlerMetadata.Aggregate

    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Aggregate
    ): HandlerFunction<ServerResponse> {
        createdMetadata = metadata
        return HandlerFunction { ServerResponse.ok().build() }
    }
}

private class TestCommandFactory(
    handlerKey: String
) : CommandRouteHandlerFunctionFactorySupport(handlerKey) {
    lateinit var createdMetadata: HttpRouteHandlerMetadata.Command

    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Command
    ): HandlerFunction<ServerResponse> {
        createdMetadata = metadata
        return HandlerFunction { ServerResponse.ok().build() }
    }
}

private class TestNoMetadataFactory(
    handlerKey: String
) : NoMetadataRouteHandlerFunctionFactorySupport(handlerKey) {
    lateinit var createdContract: HttpRouteContract

    override fun create(contract: HttpRouteContract): HandlerFunction<ServerResponse> {
        createdContract = contract
        return HandlerFunction { ServerResponse.ok().build() }
    }
}
```

- [ ] **Step 2: Run test and verify it fails**

Run:

```bash
./gradlew :wow-webflux:test --tests "me.ahoo.wow.webflux.route.RouteHandlerFunctionFactorySupportTest"
```

Expected: FAIL because the support classes do not exist.

- [ ] **Step 3: Implement support classes**

Create `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionFactorySupport.kt`:

```kotlin
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

package me.ahoo.wow.webflux.route

import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerResponse

abstract class AggregateRouteHandlerFunctionFactorySupport(
    final override val handlerKey: String
) : HttpRouteHandlerFunctionFactory {
    final override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata
    ): HandlerFunction<ServerResponse> {
        return create(contract, metadata.requireAggregateHandlerMetadata(handlerKey))
    }

    protected abstract fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Aggregate
    ): HandlerFunction<ServerResponse>

    protected fun aggregateRouteMetadata(metadata: HttpRouteHandlerMetadata.Aggregate): AggregateRouteMetadata<*> {
        return metadata.aggregateRouteMetadata
    }

    protected fun aggregateMetadata(metadata: HttpRouteHandlerMetadata.Aggregate): AggregateMetadata<*, *> {
        return metadata.aggregateRouteMetadata.aggregateMetadata
    }
}

abstract class CommandRouteHandlerFunctionFactorySupport(
    final override val handlerKey: String
) : HttpRouteHandlerFunctionFactory {
    final override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata
    ): HandlerFunction<ServerResponse> {
        return create(contract, metadata.requireCommandHandlerMetadata(handlerKey))
    }

    protected abstract fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Command
    ): HandlerFunction<ServerResponse>
}

abstract class NoMetadataRouteHandlerFunctionFactorySupport(
    final override val handlerKey: String
) : HttpRouteHandlerFunctionFactory {
    final override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata
    ): HandlerFunction<ServerResponse> {
        return create(contract)
    }

    protected abstract fun create(contract: HttpRouteContract): HandlerFunction<ServerResponse>
}
```

- [ ] **Step 4: Run support tests**

Run:

```bash
./gradlew :wow-webflux:test --tests "me.ahoo.wow.webflux.route.RouteHandlerFunctionFactorySupportTest" --tests "me.ahoo.wow.webflux.route.HttpRouteHandlerMetadataSupportTest"
```

Expected: PASS.

- [ ] **Step 5: Commit factory support**

```bash
git add wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionFactorySupport.kt \
  wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionFactorySupportTest.kt
git commit -m "refactor(webflux): add route handler factory support"
```

---

### Task 4: Migrate query base factories to aggregate metadata support

**Files:**
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/CountQueryHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/ListQueryHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/PagedQueryHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/SingleQueryHandlerFunction.kt`
- Test: existing query, event, and snapshot handler factory tests.

- [ ] **Step 1: Convert `CountQueryHandlerFunctionFactory`**

In `CountQueryHandlerFunction.kt`, remove imports for `HttpRouteHandlerFunctionFactory` and `requireAggregateHandlerMetadata`, add import for `AggregateRouteHandlerFunctionFactorySupport`, and change the factory class to:

```kotlin
open class CountQueryHandlerFunctionFactory(
    handlerKey: String,
    private val queryHandler: QueryHandler<*>,
    private val rewriteRequestCondition: RewriteRequestCondition,
    private val exceptionHandler: RequestExceptionHandler
) : AggregateRouteHandlerFunctionFactorySupport(handlerKey) {
    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Aggregate
    ): HandlerFunction<ServerResponse> {
        return create(aggregateMetadata(metadata))
    }

    private fun create(aggregateMetadata: AggregateMetadata<*, *>): HandlerFunction<ServerResponse> {
        return CountQueryHandlerFunction(
            aggregateMetadata = aggregateMetadata,
            queryHandler = queryHandler,
            rewriteRequestCondition = rewriteRequestCondition,
            exceptionHandler = exceptionHandler
        )
    }
}
```

- [ ] **Step 2: Convert `ListQueryHandlerFunctionFactory`**

In `ListQueryHandlerFunction.kt`, remove imports for `HttpRouteHandlerFunctionFactory` and `requireAggregateHandlerMetadata`, add import for `AggregateRouteHandlerFunctionFactorySupport`, and change the factory class to:

```kotlin
open class ListQueryHandlerFunctionFactory(
    handlerKey: String,
    private val queryHandler: QueryHandler<*>,
    private val rewriteRequestCondition: RewriteRequestCondition,
    private val exceptionHandler: RequestExceptionHandler,
    private val rewriteResult: (Flux<DynamicDocument>) -> Flux<DynamicDocument> = { it }
) : AggregateRouteHandlerFunctionFactorySupport(handlerKey) {
    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Aggregate
    ): HandlerFunction<ServerResponse> {
        return create(aggregateMetadata(metadata))
    }

    private fun create(aggregateMetadata: AggregateMetadata<*, *>): HandlerFunction<ServerResponse> {
        return ListQueryHandlerFunction(
            aggregateMetadata = aggregateMetadata,
            queryHandler = queryHandler,
            rewriteRequestCondition = rewriteRequestCondition,
            exceptionHandler = exceptionHandler,
            rewriteResult = rewriteResult
        )
    }
}
```

- [ ] **Step 3: Convert `PagedQueryHandlerFunctionFactory`**

In `PagedQueryHandlerFunction.kt`, remove imports for `HttpRouteHandlerFunctionFactory` and `requireAggregateHandlerMetadata`, add import for `AggregateRouteHandlerFunctionFactorySupport`, and change the factory class to:

```kotlin
open class PagedQueryHandlerFunctionFactory(
    handlerKey: String,
    private val queryHandler: QueryHandler<*>,
    private val rewriteRequestCondition: RewriteRequestCondition,
    private val exceptionHandler: RequestExceptionHandler,
    private val rewriteResult: (Mono<PagedList<DynamicDocument>>) -> Mono<PagedList<DynamicDocument>> = { it }
) : AggregateRouteHandlerFunctionFactorySupport(handlerKey) {
    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Aggregate
    ): HandlerFunction<ServerResponse> {
        return create(aggregateMetadata(metadata))
    }

    private fun create(aggregateMetadata: AggregateMetadata<*, *>): HandlerFunction<ServerResponse> {
        return PagedQueryHandlerFunction(
            aggregateMetadata = aggregateMetadata,
            queryHandler = queryHandler,
            rewriteRequestCondition = rewriteRequestCondition,
            exceptionHandler = exceptionHandler,
            rewriteResult = rewriteResult
        )
    }
}
```

- [ ] **Step 4: Convert `SingleQueryHandlerFunctionFactory`**

In `SingleQueryHandlerFunction.kt`, remove imports for `HttpRouteHandlerFunctionFactory` and `requireAggregateHandlerMetadata`, add import for `AggregateRouteHandlerFunctionFactorySupport`, and change the factory class to:

```kotlin
open class SingleQueryHandlerFunctionFactory(
    handlerKey: String,
    private val queryHandler: QueryHandler<*>,
    private val rewriteRequestCondition: RewriteRequestCondition,
    private val exceptionHandler: RequestExceptionHandler,
    private val rewriteResult: (Mono<DynamicDocument>) -> Mono<DynamicDocument> = { it }
) : AggregateRouteHandlerFunctionFactorySupport(handlerKey) {
    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Aggregate
    ): HandlerFunction<ServerResponse> {
        return create(aggregateMetadata(metadata))
    }

    private fun create(aggregateMetadata: AggregateMetadata<*, *>): HandlerFunction<ServerResponse> {
        return SingleQueryHandlerFunction(
            aggregateMetadata = aggregateMetadata,
            queryHandler = queryHandler,
            rewriteRequestCondition = rewriteRequestCondition,
            exceptionHandler = exceptionHandler,
            rewriteResult = rewriteResult
        )
    }
}
```

- [ ] **Step 5: Run query and derived handler tests**

Run:

```bash
./gradlew :wow-webflux:test --tests "me.ahoo.wow.webflux.route.query.*" --tests "me.ahoo.wow.webflux.route.event.*" --tests "me.ahoo.wow.webflux.route.snapshot.*"
```

Expected: PASS.

- [ ] **Step 6: Commit query factory migration**

```bash
git add wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/CountQueryHandlerFunction.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/ListQueryHandlerFunction.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/PagedQueryHandlerFunction.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/SingleQueryHandlerFunction.kt
git commit -m "refactor(webflux): use aggregate support for query factories"
```

---

### Task 5: Migrate direct aggregate route factories

**Files:**
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/event/LoadEventStreamHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/event/EventCompensateHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/event/state/ResendStateEventFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/BatchRegenerateSnapshotHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/LoadSnapshotHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/RegenerateSnapshotHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/state/LoadAggregateHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/state/LoadTimeBasedAggregateHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/state/LoadVersionedAggregateHandlerFunction.kt`

- [ ] **Step 1: Apply aggregate support to aggregate-metadata factories**

For factories that construct handlers with `AggregateMetadata<*, *>`, use this method body:

```kotlin
    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Aggregate
    ): HandlerFunction<ServerResponse> {
        return create(aggregateMetadata(metadata))
    }
```

Apply it to these classes:

- `LoadEventStreamHandlerFunctionFactory`
- `EventCompensateHandlerFunctionFactory`
- `ResendStateEventFunctionFactory`
- `BatchRegenerateSnapshotHandlerFunctionFactory`
- `RegenerateSnapshotHandlerFunctionFactory`
- `AggregateTracingHandlerFunctionFactory`

Use these exact base-class constructor calls and remove the import of `requireAggregateHandlerMetadata` from each file:

- `LoadEventStreamHandlerFunctionFactory` extends `AggregateRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.Event.LOAD)`
- `EventCompensateHandlerFunctionFactory` extends `AggregateRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.Event.COMPENSATE)`
- `ResendStateEventFunctionFactory` extends `AggregateRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.Event.RESEND_STATE)`
- `BatchRegenerateSnapshotHandlerFunctionFactory` extends `AggregateRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.Snapshot.BATCH_REGENERATE)`
- `RegenerateSnapshotHandlerFunctionFactory` extends `AggregateRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.Snapshot.REGENERATE)`
- `AggregateTracingHandlerFunctionFactory` extends `AggregateRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.State.AGGREGATE_TRACING)`

- [ ] **Step 2: Apply aggregate support to aggregate-route-metadata factories**

For factories that construct handlers with `AggregateRouteMetadata<*>`, use this method body:

```kotlin
    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Aggregate
    ): HandlerFunction<ServerResponse> {
        return create(aggregateRouteMetadata(metadata))
    }
```

Apply it to these classes:

- `LoadSnapshotHandlerFunctionFactory`
- `LoadAggregateHandlerFunctionFactory`
- `LoadTimeBasedAggregateHandlerFunctionFactory`
- `LoadVersionedAggregateHandlerFunctionFactory`

Use these exact base-class constructor calls and remove the import of `requireAggregateHandlerMetadata` from each file:

- `LoadSnapshotHandlerFunctionFactory` extends `AggregateRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.Snapshot.LOAD)`
- `LoadAggregateHandlerFunctionFactory` extends `AggregateRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.State.LOAD_AGGREGATE)`
- `LoadTimeBasedAggregateHandlerFunctionFactory` extends `AggregateRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.State.LOAD_TIME_BASED_AGGREGATE)`
- `LoadVersionedAggregateHandlerFunctionFactory` extends `AggregateRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.State.LOAD_VERSIONED_AGGREGATE)`

- [ ] **Step 3: Run direct aggregate handler tests**

Run:

```bash
./gradlew :wow-webflux:test --tests "me.ahoo.wow.webflux.route.event.LoadEventStreamHandlerFunctionTest" --tests "me.ahoo.wow.webflux.route.compensation.EventCompensateHandlerFunctionTest" --tests "me.ahoo.wow.webflux.route.state.ResendStateEventHandlerFunctionTest" --tests "me.ahoo.wow.webflux.route.snapshot.BatchRegenerateSnapshotHandlerFunctionTest" --tests "me.ahoo.wow.webflux.route.snapshot.LoadSnapshotHandlerFunctionTest" --tests "me.ahoo.wow.webflux.route.snapshot.RegenerateSnapshotHandlerFunctionTest" --tests "me.ahoo.wow.webflux.route.state.AggregateTracingHandlerFunctionTest" --tests "me.ahoo.wow.webflux.route.state.LoadAggregateHandlerFunctionTest" --tests "me.ahoo.wow.webflux.route.state.LoadTimeBasedAggregateHandlerFunctionTest" --tests "me.ahoo.wow.webflux.route.state.LoadVersionedAggregateHandlerFunctionTest"
```

Expected: PASS.

- [ ] **Step 4: Commit direct aggregate migration**

```bash
git add wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/event/LoadEventStreamHandlerFunction.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/event/EventCompensateHandlerFunction.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/event/state/ResendStateEventFunction.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/BatchRegenerateSnapshotHandlerFunction.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/LoadSnapshotHandlerFunction.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/RegenerateSnapshotHandlerFunction.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingHandlerFunction.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/state/LoadAggregateHandlerFunction.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/state/LoadTimeBasedAggregateHandlerFunction.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/state/LoadVersionedAggregateHandlerFunction.kt
git commit -m "refactor(webflux): use aggregate support for route factories"
```

---

### Task 6: Migrate command and no-metadata factories

**Files:**
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/command/CommandHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/command/CommandFacadeHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/global/GenerateBIScriptHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/global/GetWowMetadataHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/global/GlobalIdHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/wait/CommandWaitHandlerFunction.kt`

- [ ] **Step 1: Convert command metadata factory**

In `CommandHandlerFunction.kt`, remove the import of `requireCommandHandlerMetadata`, add import for `CommandRouteHandlerFunctionFactorySupport`, and change `CommandHandlerFunctionFactory` to extend `CommandRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.Command.COMMAND)`.

Use this create override:

```kotlin
    @Suppress("UNCHECKED_CAST")
    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Command
    ): HandlerFunction<ServerResponse> {
        return create(
            aggregateRouteMetadata = metadata.aggregateRouteMetadata,
            commandRouteMetadata = metadata.commandRouteMetadata as CommandRouteMetadata<Any>
        )
    }
```

- [ ] **Step 2: Convert no-metadata global factories**

Each factory below must extend the exact support base listed here, remove imports for `HttpRouteHandlerFunctionFactory` and `HttpRouteHandlerMetadata`, and replace `override fun create(contract, metadata)` with `override fun create(contract: HttpRouteContract)`.

- `CommandFacadeHandlerFunctionFactory` extends `NoMetadataRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.Global.COMMAND_FACADE)`
- `GenerateBIScriptHandlerFunctionFactory` extends `NoMetadataRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT)`
- `GetWowMetadataHandlerFunctionFactory` extends `NoMetadataRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.Global.METADATA)`
- `GlobalIdHandlerFunctionFactory` extends `NoMetadataRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.Global.GLOBAL_ID)`
- `CommandWaitHandlerFunctionFactory` extends `NoMetadataRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.Global.COMMAND_WAIT)`

Use these exact create methods:

`CommandFacadeHandlerFunctionFactory`:

```kotlin
    override fun create(contract: HttpRouteContract): HandlerFunction<ServerResponse> {
        return CommandFacadeHandlerFunction(
            commandGateway = commandGateway,
            commandMessageExtractor = commandMessageExtractor,
            exceptionHandler = exceptionHandler,
            commandWaitPolicy = commandWaitPolicy
        )
    }
```

`GenerateBIScriptHandlerFunctionFactory`:

```kotlin
    override fun create(contract: HttpRouteContract): HandlerFunction<ServerResponse> {
        return GenerateBIScriptHandlerFunction(kafkaBootstrapServers, topicPrefix)
    }
```

`GetWowMetadataHandlerFunctionFactory`:

```kotlin
    override fun create(contract: HttpRouteContract): HandlerFunction<ServerResponse> {
        return GetWowMetadataHandlerFunction()
    }
```

`GlobalIdHandlerFunctionFactory`:

```kotlin
    override fun create(contract: HttpRouteContract): HandlerFunction<ServerResponse> {
        return GlobalIdHandlerFunction()
    }
```

`CommandWaitHandlerFunctionFactory`:

```kotlin
    override fun create(contract: HttpRouteContract): HandlerFunction<ServerResponse> {
        return CommandWaitHandlerFunction(waitCoordinator)
    }
```

- [ ] **Step 3: Remove private `createHandlerFunction` methods**

Delete private `createHandlerFunction()` methods from the no-metadata factories after moving their bodies into `create(contract)`.

- [ ] **Step 4: Run command/global/wait tests**

Run:

```bash
./gradlew :wow-webflux:test --tests "me.ahoo.wow.webflux.route.command.*" --tests "me.ahoo.wow.webflux.route.bi.GenerateBIScriptHandlerFunctionTest" --tests "me.ahoo.wow.webflux.route.id.GlobalIdHandlerFunctionTest" --tests "me.ahoo.wow.webflux.route.metadata.GetWowMetadataHandlerFunctionTest" --tests "me.ahoo.wow.webflux.wait.CommandWaitHandlerFunctionTest"
```

Expected: PASS.

- [ ] **Step 5: Commit command and no-metadata migration**

```bash
git add wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/command/CommandHandlerFunction.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/command/CommandFacadeHandlerFunction.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/global/GenerateBIScriptHandlerFunction.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/global/GetWowMetadataHandlerFunction.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/global/GlobalIdHandlerFunction.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/wait/CommandWaitHandlerFunction.kt
git commit -m "refactor(webflux): use typed support for command and global factories"
```

---

### Task 7: Verify starter assembly boundaries

**Files:**
- Modify if needed: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfigurationTest.kt`
- Modify only if constructor wiring changes: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfiguration.kt`

- [ ] **Step 1: Run current starter WebFlux tests**

Run:

```bash
./gradlew :wow-spring-boot-starter:test --tests "me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfigurationTest"
```

Expected: PASS.

- [ ] **Step 2: Add a regression assertion only if the new runtime API changes Spring wiring**

If `WebFluxAutoConfiguration.commandRouterFunction(...)` still constructs `RouterFunctionBuilder(routerSpecs, routeHandlerFunctionRegistrar)`, do not change production starter code.

If constructor arguments change, update `commandRouterFunction` to keep starter as assembly only:

```kotlin
    @Bean
    fun commandRouterFunction(
        routerSpecs: RouterSpecs,
        routeHandlerFunctionRegistrar: RouteHandlerFunctionRegistrar
    ): RouterFunction<ServerResponse> {
        return RouterFunctionBuilder(
            routerSpecs = routerSpecs,
            routeHandlerFunctionRegistrar = routeHandlerFunctionRegistrar
        ).build()
    }
```

- [ ] **Step 3: Run OpenAPI starter companion test**

Run:

```bash
./gradlew :wow-spring-boot-starter:test --tests "me.ahoo.wow.spring.boot.starter.openapi.OpenAPIAutoConfigurationTest" --tests "me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfigurationTest"
```

Expected: PASS.

- [ ] **Step 4: Commit starter boundary changes only if files changed**

If no starter files changed, skip this commit.

If starter files changed, run:

```bash
git add wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfiguration.kt \
  wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfigurationTest.kt
git commit -m "test(webflux): lock starter route factory assembly"
```

---

### Task 8: Final debt cleanup and REST compatibility verification

**Files:**
- Inspect all modified `wow-webflux` files.
- Inspect modified `wow-spring-boot-starter` files if any.
- Inspect OpenAPI snapshot status.

- [ ] **Step 1: Scan for migration leftovers**

Run:

```bash
rg -n "TODO|FIXME|legacy|compatibility shim|@Deprecated|requireAggregateHandlerMetadata|requireCommandHandlerMetadata|createHandlerFunction\\(" wow-webflux/src/main wow-webflux/src/test wow-spring-boot-starter/src/main wow-spring-boot-starter/src/test
```

Expected:
- No `TODO`, `FIXME`, `legacy`, `compatibility shim`, or `@Deprecated` introduced by this refactor.
- `requireAggregateHandlerMetadata` and `requireCommandHandlerMetadata` should remain only in `HttpRouteHandlerMetadataSupport.kt` and its test.
- `createHandlerFunction(` should not remain in migrated no-metadata factories.

- [ ] **Step 2: Run WebFlux full check**

Run:

```bash
./gradlew --rerun-tasks :wow-webflux:check
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run OpenAPI REST contract check**

Run:

```bash
./gradlew --rerun-tasks :wow-openapi:check
```

Expected: `BUILD SUCCESSFUL` and no unexpected snapshot changes.

- [ ] **Step 4: Run starter WebFlux/OpenAPI check**

Run:

```bash
./gradlew --rerun-tasks :wow-spring-boot-starter:test --tests "me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfigurationTest" --tests "me.ahoo.wow.spring.boot.starter.openapi.OpenAPIAutoConfigurationTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Verify REST contract files**

Run:

```bash
git diff --exit-code origin/main..HEAD -- wow-openapi/src/test/resources/openapi/example-domain-contract.snapshot.json wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json
```

Expected: no diff. If there is a diff, inspect it and do not commit until it is confirmed to preserve path, method, accept, and route id compatibility.

- [ ] **Step 6: Verify formatting and worktree**

Run:

```bash
git diff --check origin/main..HEAD
git status --short --branch
```

Expected:
- `git diff --check` prints nothing and exits 0.
- `git status --short --branch` shows only the current branch line or intentional committed-ahead state.

- [ ] **Step 7: Commit cleanup if needed**

If cleanup edits were made after previous commits, run:

```bash
git add wow-webflux wow-spring-boot-starter
git commit -m "chore(webflux): clean runtime contract leftovers"
```

Skip this commit if no cleanup edits were made.

---

## Final Review Checklist

- [ ] `RouterFunctionBuilder` no longer performs factory lookup or predicate construction inline.
- [ ] `RouteHandlerFunctionRegistrar` owns registry lookup and missing-factory diagnostics.
- [ ] Aggregate metadata extraction is centralized in `AggregateRouteHandlerFunctionFactorySupport`.
- [ ] Command metadata extraction is centralized in `CommandRouteHandlerFunctionFactorySupport`.
- [ ] No-metadata/global factories no longer carry unused `metadata` boilerplate.
- [ ] `wow-spring-boot-starter` remains assembly-only.
- [ ] RESTful API compatibility is verified by `wow-openapi`, `wow-webflux`, and starter tests.
- [ ] No public API compatibility shim was added.
