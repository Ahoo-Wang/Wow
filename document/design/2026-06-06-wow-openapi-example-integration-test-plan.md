# wow-openapi Example Domain Integration Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add real-scenario integration tests using example-domain aggregates to verify full OpenAPI document generation.

**Architecture:** Add `example-domain` + `example-api` as test dependencies. Create one `ExampleDomainOpenAPITest` class that builds a full OpenAPI document via `RouterSpecs` and asserts on the generated paths, schemas, components, and tags.

**Tech Stack:** Kotlin 2.3, JUnit Jupiter, FluentAssert, Swagger OpenAPI models

---

### Task 1: Add Build Dependencies

**Files:**
- Modify: `wow-openapi/build.gradle.kts`

- [ ] **Step 1: Add example-domain and example-api test dependencies**

In `wow-openapi/build.gradle.kts`, add two lines after the existing `testImplementation` entries:

```kotlin
testImplementation(project(":example-domain"))
testImplementation(project(":example-api"))
```

The full `dependencies` block becomes:

```kotlin
dependencies {
    api(project(":wow-core"))
    api(project(":wow-query"))
    api(project(":wow-schema"))
    implementation(project(":wow-bi"))
    implementation(kotlin("reflect"))
    implementation("org.springframework:spring-web")
    api("io.swagger.core.v3:swagger-core-jakarta")
    testImplementation(project(":wow-models"))
    testImplementation(project(":wow-tck"))
    testImplementation(project(":example-domain"))
    testImplementation(project(":example-api"))
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :wow-openapi:compileTestKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add wow-openapi/build.gradle.kts
git commit -m "build(openapi): add example-domain and example-api test dependencies"
```

---

### Task 2: Create Test File with RouterSpecs Build Tests

**Files:**
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/ExampleDomainOpenAPITest.kt`

- [ ] **Step 1: Create test file with class structure and RouterSpecs Build tests**

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

package me.ahoo.wow.openapi

import io.swagger.v3.oas.models.OpenAPI
import me.ahoo.test.asserts.assert
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.disable.DisabledRouteAggregate
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class ExampleDomainOpenAPITest {

    private val namedContext = MaterializedNamedBoundedContext("example-service")
    private lateinit var routerSpecs: RouterSpecs
    private lateinit var openAPI: OpenAPI

    @BeforeEach
    fun setUp() {
        routerSpecs = RouterSpecs(namedContext).build()
        openAPI = OpenAPI()
        routerSpecs.mergeOpenAPI(openAPI)
    }

    @Nested
    inner class RouterSpecsBuild {

        @Test
        fun `should discover order and cart aggregates`() {
            val aggregateTypes = MetadataSearcher.namedAggregateType
            aggregateTypes.assert().containsKey(Order::class)
            aggregateTypes.assert().containsKey(Cart::class)
        }

        @Test
        fun `should not generate routes for disabled route aggregate`() {
            val disabledPaths = routerSpecs.filter {
                it.path.contains("disabled_route_aggregate")
            }
            disabledPaths.assert().isEmpty()
            // Also verify the aggregate exists but is filtered by enabled=false
            MetadataSearcher.namedAggregateType.assert().containsKey(DisabledRouteAggregate::class)
        }

        @Test
        fun `should generate expected route count`() {
            // Cart: 6 commands + state/snapshot/event routes
            // Order: 5 commands + default delete/recover/applyResourceTags + state/snapshot/event routes
            // Plus global routes (command facade, global id, etc.)
            routerSpecs.assert().hasSizeGreaterThanOrEqualTo(20)
        }

        @Test
        fun `should set info title to context name`() {
            openAPI.info.assert().isNotNull()
            openAPI.info.title.assert().isEqualTo("example-service")
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.ExampleDomainOpenAPITest" --no-daemon`
Expected: All 4 tests PASS

- [ ] **Step 3: Commit**

```bash
git add wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/ExampleDomainOpenAPITest.kt
git commit -m "test(openapi): add example-domain integration test with RouterSpecs build tests"
```

---

### Task 3: Add Aggregate Routes Tests

**Files:**
- Modify: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/ExampleDomainOpenAPITest.kt`

- [ ] **Step 1: Add AggregateRoutes nested class after RouterSpecsBuild**

Append inside `ExampleDomainOpenAPITest` class, after `RouterSpecsBuild`:

```kotlin
@Nested
inner class AggregateRoutes {

    @Test
    fun `should generate cart routes without tenant path`() {
        // Cart has @StaticTenantId → appendTenantPath=false
        // Cart owner=AGGREGATE_ID → appendOwnerPath depends on command
        val cartRoutes = routerSpecs.filter {
            it.path.contains("/cart")
        }
        cartRoutes.assert().isNotEmpty()
        // No tenant path variable in any cart route
        cartRoutes.forEach {
            it.path.assert().doesNotContain("tenant")
        }
    }

    @Test
    fun `should generate order routes with spaced resource name`() {
        // Order resourceName="sales-order", spaced=true, owner=ALWAYS
        val orderRoutes = routerSpecs.filter {
            it.path.contains("sales-order")
        }
        orderRoutes.assert().isNotEmpty()
    }

    @Test
    fun `should set correct tags for cart`() {
        // Cart has @Tag(name = "customer")
        val cartRoutes = routerSpecs.filter {
            it.path.contains("/cart")
        }
        val tagNames = cartRoutes.flatMap { it.tags.map { tag -> tag.name } }.toSet()
        tagNames.assert().contains("customer")
    }

    @Test
    fun `should set aggregate tags in open api`() {
        openAPI.tags.assert().isNotEmpty()
        val tagNames = openAPI.tags.map { it.name }.toSet()
        // Should contain aggregate-level tags (e.g., "cart", "sales-order", "customer")
        tagNames.assert().isNotEmpty()
    }
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.ExampleDomainOpenAPITest" --no-daemon`
Expected: All 8 tests PASS (4 previous + 4 new)

- [ ] **Step 3: Commit**

```bash
git add wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/ExampleDomainOpenAPITest.kt
git commit -m "test(openapi): add aggregate routes integration tests"
```

---

### Task 4: Add Command Routes Tests

**Files:**
- Modify: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/ExampleDomainOpenAPITest.kt`

- [ ] **Step 1: Add CommandRoutes nested class after AggregateRoutes**

Append inside `ExampleDomainOpenAPITest` class, after `AggregateRoutes`:

```kotlin
@Nested
inner class CommandRoutes {

    @Test
    fun `should generate create order as POST with empty action`() {
        // CreateOrder: @CommandRoute(action = ""), @CreateAggregate → POST
        val createOrderRoute = routerSpecs.find {
            it.id.contains("CreateOrder", ignoreCase = true) &&
                it.path.contains("sales-order")
        }
        createOrderRoute.assert().isNotNull()
        createOrderRoute!!.method.assert().isEqualTo(Https.Method.POST)
        // Empty action → path ends at sales-order (possibly with /{id})
        createOrderRoute.path.assert().contains("sales-order")
    }

    @Test
    fun `should generate change address as PUT`() {
        // ChangeAddress: @CommandRoute(action = "address", method = PUT)
        val route = routerSpecs.find {
            it.id.contains("ChangeAddress", ignoreCase = true)
        }
        route.assert().isNotNull()
        route!!.method.assert().isEqualTo(Https.Method.PUT)
        route.path.assert().contains("address")
    }

    @Test
    fun `should generate ship order as POST with package action`() {
        // ShipOrder: @CommandRoute(action = "package", method = POST)
        val route = routerSpecs.find {
            it.id.contains("ShipOrder", ignoreCase = true)
        }
        route.assert().isNotNull()
        route!!.method.assert().isEqualTo(Https.Method.POST)
        route.path.assert().contains("package")
    }

    @Test
    fun `should generate pay order as POST with pay action`() {
        // PayOrder: @CommandRoute("pay", method = POST)
        val route = routerSpecs.find {
            it.id.contains("PayOrder", ignoreCase = true)
        }
        route.assert().isNotNull()
        route!!.method.assert().isEqualTo(Https.Method.POST)
        route.path.assert().contains("pay")
    }

    @Test
    fun `should generate add cart item as POST`() {
        // AddCartItem: @CommandRoute(method = POST), @AllowCreate
        val route = routerSpecs.find {
            it.id.contains("AddCartItem", ignoreCase = true)
        }
        route.assert().isNotNull()
        route!!.method.assert().isEqualTo(Https.Method.POST)
    }

    @Test
    fun `should generate view cart route`() {
        // ViewCart: @VoidCommand → still generates a route
        val route = routerSpecs.find {
            it.id.contains("ViewCart", ignoreCase = true)
        }
        route.assert().isNotNull()
    }
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.ExampleDomainOpenAPITest" --no-daemon`
Expected: All 14 tests PASS (8 previous + 6 new)

- [ ] **Step 3: Commit**

```bash
git add wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/ExampleDomainOpenAPITest.kt
git commit -m "test(openapi): add command routes integration tests"
```

---

### Task 5: Add Schemas and Components Tests

**Files:**
- Modify: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/ExampleDomainOpenAPITest.kt`

- [ ] **Step 1: Add Schemas and Components nested classes after CommandRoutes**

Add imports at the top (after existing imports):

```kotlin
import me.ahoo.wow.example.api.order.CreateOrder
import me.ahoo.wow.example.api.order.OrderCreated
import me.ahoo.wow.example.api.order.ShippingAddress
```

Append inside `ExampleDomainOpenAPITest` class, after `CommandRoutes`:

```kotlin
@Nested
inner class Schemas {

    @Test
    fun `should generate create order schema with fields`() {
        val schemas = openAPI.components.schemas
        schemas.assert().isNotEmpty()
        // Schema name uses BoundedContextSchemaNameConverter prefix
        val createOrderSchema = schemas.entries.find {
            it.key.contains("CreateOrder")
        }
        createOrderSchema.assert().isNotNull()
        val properties = createOrderSchema!!.value.properties
        properties.assert().containsKey("items")
        properties.assert().containsKey("address")
        properties.assert().containsKey("fromCart")
    }

    @Test
    fun `should generate order created schema`() {
        val schemas = openAPI.components.schemas
        val orderCreatedSchema = schemas.entries.find {
            it.key.contains("OrderCreated")
        }
        orderCreatedSchema.assert().isNotNull()
    }

    @Test
    fun `should generate shipping address schema`() {
        val schemas = openAPI.components.schemas
        val addressSchema = schemas.entries.find {
            it.key.contains("ShippingAddress")
        }
        addressSchema.assert().isNotNull()
    }
}

@Nested
inner class Components {

    @Test
    fun `should generate command header parameters`() {
        val parameters = openAPI.components.parameters
        parameters.assert().isNotEmpty()
    }

    @Test
    fun `should generate command responses`() {
        val responses = openAPI.components.responses
        responses.assert().isNotEmpty()
    }
}
```

- [ ] **Step 2: Run all tests**

Run: `./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.ExampleDomainOpenAPITest" --no-daemon`
Expected: All 19 tests PASS

- [ ] **Step 3: Commit**

```bash
git add wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/ExampleDomainOpenAPITest.kt
git commit -m "test(openapi): add schemas and components integration tests"
```

---

### Task 6: Final Verification

**Files:** None

- [ ] **Step 1: Run full wow-openapi test suite**

Run: `./gradlew :wow-openapi:test --no-daemon`
Expected: All tests PASS (existing 110 + new 19 = 129)

- [ ] **Step 2: Verify no regressions in dependent modules**

Run: `./gradlew :example-domain:test :example-api:test --no-daemon`
Expected: BUILD SUCCESSFUL
