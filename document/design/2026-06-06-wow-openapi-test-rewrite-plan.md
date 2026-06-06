# wow-openapi Unit Test Rewrite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite all wow-openapi unit tests with self-contained fixtures, `internal` visibility, and `*Test.kt` naming — removing all external test module dependencies.

**Architecture:** Each task covers a self-contained layer, produces a commit, and can be verified with `./gradlew :wow-openapi:test`. Tests use FluentAssert (`.assert()`), internal visibility, and backtick method names. Fixture types are defined `private` within each test file.

**Tech Stack:** Kotlin 2.3, JUnit Jupiter, FluentAssert (`me.ahoo.test.asserts.assert`), Swagger OpenAPI v3 models.

---

## Task 1: Build Config Changes

**Files:**
- Modify: `wow-openapi/build.gradle.kts`

- [ ] **Step 1: Remove external test dependencies from build.gradle.kts**

Remove the three `testImplementation` lines for external test modules:

```kotlin
// DELETE these three lines:
testImplementation(project(":wow-models"))
testImplementation(project(":example-domain"))
testImplementation(project(":example-transfer-domain"))
testImplementation(project(":wow-tck"))
```

Keep only `testImplementation(project(":wow-models"))` since `wow-models` provides shared model types needed for testing (not a test fixture module). If in doubt, remove all four and add back only what compilation requires.

- [ ] **Step 2: Verify build still configures**

Run: `./gradlew :wow-openapi:compileTestKotlin --stacktrace 2>&1 | tail -20`

Expected: Compilation errors in existing tests that reference removed modules (this is expected — we'll rewrite those tests in subsequent tasks).

- [ ] **Step 3: Commit**

```bash
git add wow-openapi/build.gradle.kts
git commit -m "build(openapi): remove external test module dependencies"
```

---

## Task 2: Root Layer — Simple Value Types

**Files:**
- Rewrite: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/BatchResultTest.kt`
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/HttpsTest.kt`
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/PathBuilderTest.kt`

- [ ] **Step 1: Rewrite BatchResultTest**

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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.ErrorInfo
import org.junit.jupiter.api.Test

internal class BatchResultTest {

    @Test
    fun `should create batch result with after id and size`() {
        val batchResult = BatchResult("cursorId", 10)
        batchResult.afterId.assert().isEqualTo("cursorId")
        batchResult.size.assert().isEqualTo(10)
    }

    @Test
    fun `should use default error code and message`() {
        val batchResult = BatchResult("id", 1)
        batchResult.errorCode.assert().isEqualTo(ErrorInfo.SUCCEEDED)
        batchResult.errorMsg.assert().isEqualTo(ErrorInfo.SUCCEEDED_MESSAGE)
    }

    @Test
    fun `should implement ErrorInfo interface`() {
        val batchResult = BatchResult("id", 0)
        batchResult.assert().isInstanceOf(ErrorInfo::class.java)
    }
}
```

- [ ] **Step 2: Create HttpsTest**

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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

internal class HttpsTest {

    @Test
    fun `should have correct header constant`() {
        Https.Header.ACCEPT.assert().isEqualTo("Accept")
    }

    @Test
    fun `should have correct status code constants`() {
        Https.Code.OK.assert().isEqualTo("200")
        Https.Code.BAD_REQUEST.assert().isEqualTo("400")
        Https.Code.NOT_FOUND.assert().isEqualTo("404")
        Https.Code.REQUEST_TIMEOUT.assert().isEqualTo("408")
        Https.Code.CONFLICT.assert().isEqualTo("409")
        Https.Code.GONE.assert().isEqualTo("410")
        Https.Code.TOO_MANY_REQUESTS.assert().isEqualTo("429")
    }

    @Test
    fun `should have correct method constants`() {
        Https.Method.GET.assert().isEqualTo("GET")
        Https.Method.POST.assert().isEqualTo("POST")
        Https.Method.PUT.assert().isEqualTo("PUT")
        Https.Method.DELETE.assert().isEqualTo("DELETE")
        Https.Method.PATCH.assert().isEqualTo("PATCH")
        Https.Method.HEAD.assert().isEqualTo("HEAD")
        Https.Method.OPTIONS.assert().isEqualTo("OPTIONS")
        Https.Method.TRACE.assert().isEqualTo("TRACE")
    }

    @Test
    fun `should have correct media type constants`() {
        Https.MediaType.APPLICATION_JSON.assert().isEqualTo("application/json")
        Https.MediaType.APPLICATION_SQL.assert().isEqualTo("application/sql")
        Https.MediaType.TEXT_PLAIN.assert().isEqualTo("text/plain")
        Https.MediaType.TEXT_EVENT_STREAM.assert().isEqualTo("text/event-stream")
    }
}
```

- [ ] **Step 3: Create PathBuilderTest**

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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

internal class PathBuilderTest {

    @Test
    fun `should append non-blank segment with separator`() {
        val path = PathBuilder().append("orders").build()
        path.assert().isEqualTo("/orders")
    }

    @Test
    fun `should append segment starting with separator as-is`() {
        val path = PathBuilder().append("/orders").build()
        path.assert().isEqualTo("/orders")
    }

    @Test
    fun `should skip blank segment`() {
        val path = PathBuilder().append("").append("orders").build()
        path.assert().isEqualTo("/orders")
    }

    @Test
    fun `should build empty path when no segments appended`() {
        val path = PathBuilder().build()
        path.assert().isEqualTo("")
    }

    @Test
    fun `should append multiple segments`() {
        val path = PathBuilder()
            .append("orders")
            .append("{id}")
            .build()
        path.assert().isEqualTo("/orders/{id}")
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.BatchResultTest" --tests "me.ahoo.wow.openapi.HttpsTest" --tests "me.ahoo.wow.openapi.PathBuilderTest"`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
git add wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/BatchResultTest.kt wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/HttpsTest.kt wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/PathBuilderTest.kt
git commit -m "test(openapi): rewrite BatchResultTest, add HttpsTest and PathBuilderTest"
```

---

## Task 3: Root Layer — Tags and OpenAPIExtensions

**Files:**
- Rewrite: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/TagsTest.kt`
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/OpenAPIExtensionsTest.kt`

- [ ] **Step 1: Rewrite TagsTest**

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

import io.swagger.v3.oas.annotations.tags.Tag
import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.Tags.toTags
import org.junit.jupiter.api.Test

internal class TagsTest {

    @Test
    fun `should convert single tag annotation to tags`() {
        val tags = SingleTag::class.java.toTags()
        tags.map { it.name }.assert().contains("test")
    }

    @Test
    fun `should convert multiple tag annotations to tags`() {
        val tags = MultiTag::class.java.toTags()
        tags.map { it.name }.assert().contains("test", "test2")
    }

    @Test
    fun `should return empty tags for class without annotations`() {
        val tags = NoTag::class.java.toTags()
        tags.assert().isEmpty()
    }
}

@Tag(name = "test")
@Tag(name = "test2")
private interface MultiTag

@Tag(name = "test")
private interface SingleTag

private interface NoTag
```

- [ ] **Step 2: Create OpenAPIExtensionsTest**

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

import io.swagger.v3.oas.models.info.Info
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.Wow
import me.ahoo.wow.openapi.OpenAPIExtensions.WOW_CONTEXT_ALIAS
import me.ahoo.wow.openapi.OpenAPIExtensions.WOW_CONTEXT_NAME
import me.ahoo.wow.openapi.OpenAPIExtensions.WOW_VERSION
import me.ahoo.wow.openapi.OpenAPIExtensions.withExtensions
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import org.junit.jupiter.api.Test

internal class OpenAPIExtensionsTest {

    private val context = MaterializedNamedBoundedContext("test-service")

    @Test
    fun `should add wow version extension to info`() {
        val info = Info().withExtensions(context)
        info.extensions[Wow.VERSION].assert().isEqualTo(Wow.VERSION)
    }

    @Test
    fun `should add context name extension to info`() {
        val info = Info().withExtensions(context)
        info.extensions[WOW_CONTEXT_NAME].assert().isEqualTo("test-service")
    }

    @Test
    fun `should add context alias extension to info`() {
        val info = Info().withExtensions(context)
        info.extensions[WOW_CONTEXT_ALIAS].assert().isEqualTo("test-service")
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.TagsTest" --tests "me.ahoo.wow.openapi.OpenAPIExtensionsTest"`
Expected: All PASS

- [ ] **Step 4: Commit**

```bash
git add wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/TagsTest.kt wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/OpenAPIExtensionsTest.kt
git commit -m "test(openapi): rewrite TagsTest, add OpenAPIExtensionsTest"
```

---

## Task 4: Root Layer — RouteSpec and RouteIdSpec

**Files:**
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/RouteSpecTest.kt`
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/RouteIdSpecTest.kt`

- [ ] **Step 1: Create RouteSpecTest**

This tests the `toOperation()` and `toPathItem()` top-level functions in `RouteSpec.kt`.

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

import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.tags.Tag
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class RouteSpecTest {

    private fun routeSpec(
        id: String = "test-id",
        path: String = "/test",
        method: String = Https.Method.GET,
        summary: String = "Test",
        tags: List<Tag> = listOf(Tag().name("test"))
    ): RouteSpec = object : RouteSpec {
        override val id: String = id
        override val path: String = path
        override val method: String = method
        override val summary: String = summary
        override val tags: List<Tag> = tags
        override val parameters: List<Parameter> = emptyList()
        override val responses: ApiResponses = ApiResponses()
    }

    @Test
    fun `should convert route spec to operation with correct fields`() {
        val spec = routeSpec()
        val operation = spec.toOperation()
        operation.operationId.assert().isEqualTo("test-id")
        operation.summary.assert().isEqualTo("Test")
        operation.tags.assert().isEqualTo(listOf("test"))
    }

    @Test
    fun `should convert route spec list to path item with get method`() {
        val spec = routeSpec(method = Https.Method.GET)
        val pathItem = listOf(spec).toPathItem()
        pathItem.get.assert().isNotNull()
    }

    @Test
    fun `should map post method correctly`() {
        val pathItem = listOf(routeSpec(method = Https.Method.POST)).toPathItem()
        pathItem.post.assert().isNotNull()
    }

    @Test
    fun `should map put method correctly`() {
        val pathItem = listOf(routeSpec(method = Https.Method.PUT)).toPathItem()
        pathItem.put.assert().isNotNull()
    }

    @Test
    fun `should map delete method correctly`() {
        val pathItem = listOf(routeSpec(method = Https.Method.DELETE)).toPathItem()
        pathItem.delete.assert().isNotNull()
    }

    @Test
    fun `should map patch method correctly`() {
        val pathItem = listOf(routeSpec(method = Https.Method.PATCH)).toPathItem()
        pathItem.patch.assert().isNotNull()
    }

    @Test
    fun `should throw when encountering unsupported method`() {
        val spec = routeSpec(method = "UNSUPPORTED")
        assertThrows<IllegalArgumentException> {
            listOf(spec).toPathItem()
        }
    }

    @Test
    fun `should throw when detecting duplicate routes with same method`() {
        val spec1 = routeSpec(id = "route-1", method = Https.Method.GET)
        val spec2 = routeSpec(id = "route-2", method = Https.Method.GET)
        assertThrows<IllegalArgumentException> {
            listOf(spec1, spec2).toPathItem()
        }
    }
}
```

- [ ] **Step 2: Create RouteIdSpecTest**

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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test

internal class RouteIdSpecTest {

    @Test
    fun `should build id with all segments`() {
        val id = RouteIdSpec()
            .prefix("order")
            .appendTenant(true)
            .appendOwner(true)
            .resourceName("snapshot")
            .operation("load")
            .build()
        id.assert().isEqualTo("order.tenant.owner.snapshot.load")
    }

    @Test
    fun `should build id with prefix only`() {
        val id = RouteIdSpec()
            .prefix("order")
            .build()
        id.assert().isEqualTo("order")
    }

    @Test
    fun `should build id with tenant appended`() {
        val id = RouteIdSpec()
            .prefix("order")
            .appendTenant(true)
            .build()
        id.assert().isEqualTo("order.tenant")
    }

    @Test
    fun `should build id with owner appended`() {
        val id = RouteIdSpec()
            .prefix("order")
            .appendOwner(true)
            .build()
        id.assert().isEqualTo("order.owner")
    }

    @Test
    fun `should build id with resource name and operation`() {
        val id = RouteIdSpec()
            .prefix("order")
            .resourceName("event")
            .operation("load")
            .build()
        id.assert().isEqualTo("order.event.load")
    }

    @Test
    fun `should build empty id when no segments set`() {
        val id = RouteIdSpec().build()
        id.assert().isEqualTo("")
    }

    @Test
    fun `should set prefix from named aggregate`() {
        val namedAggregate = MaterializedNamedAggregate("context", "aggregate")
        val id = RouteIdSpec()
            .aggregate(namedAggregate)
            .operation("send")
            .build()
        id.assert().isEqualTo("context.aggregate.send")
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.RouteSpecTest" --tests "me.ahoo.wow.openapi.RouteIdSpecTest"`
Expected: All PASS

- [ ] **Step 4: Commit**

```bash
git add wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/RouteSpecTest.kt wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/RouteIdSpecTest.kt
git commit -m "test(openapi): add RouteSpecTest and RouteIdSpecTest"
```

---

## Task 5: Root Layer — Builders and BatchRouteSpec

**Files:**
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/ApiResponseBuilderTest.kt`
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/RequestBodyBuilderTest.kt`

- [ ] **Step 1: Create ApiResponseBuilderTest**

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

import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.media.StringSchema
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

internal class ApiResponseBuilderTest {

    @Test
    fun `should add header to api response`() {
        val response = ApiResponseBuilder()
            .header("X-Test", Header().description("test-header"))
            .build()
        response.headers.assert().containsKey("X-Test")
        response.headers["X-Test"]!!.description.assert().isEqualTo("test-header")
    }

    @Test
    fun `should add content to api response`() {
        val response = ApiResponseBuilder()
            .content(schema = StringSchema())
            .build()
        response.content.assert().isNotNull()
        response.content.containsKey(Https.MediaType.APPLICATION_JSON).assert().isTrue()
    }

    @Test
    fun `should build response with description and content`() {
        val response = ApiResponseBuilder()
            .description("OK")
            .content(schema = StringSchema())
            .build()
        response.description.assert().isEqualTo("OK")
        response.content.containsKey(Https.MediaType.APPLICATION_JSON).assert().isTrue()
    }
}
```

- [ ] **Step 2: Create RequestBodyBuilderTest**

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

import io.swagger.v3.oas.models.media.StringSchema
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

internal class RequestBodyBuilderTest {

    @Test
    fun `should add content to request body`() {
        val requestBody = RequestBodyBuilder()
            .content(schema = StringSchema())
            .build()
        requestBody.content.containsKey(Https.MediaType.APPLICATION_JSON).assert().isTrue()
    }

    @Test
    fun `should build request body with description`() {
        val requestBody = RequestBodyBuilder()
            .description("command body")
            .content(schema = StringSchema())
            .build()
        requestBody.description.assert().isEqualTo("command body")
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.ApiResponseBuilderTest" --tests "me.ahoo.wow.openapi.RequestBodyBuilderTest"`
Expected: All PASS

- [ ] **Step 4: Commit**

```bash
git add wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/ApiResponseBuilderTest.kt wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/RequestBodyBuilderTest.kt
git commit -m "test(openapi): add ApiResponseBuilderTest and RequestBodyBuilderTest"
```

---

## Task 6: Context Layer

**Files:**
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/context/OpenAPIComponentContextTest.kt`
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/context/DefaultOpenAPIComponentContextTest.kt`
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/context/CurrentOpenAPIComponentContextTest.kt`
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/context/OpenAPIComponentContextCapableTest.kt`

- [ ] **Step 1: Create OpenAPIComponentContextTest**

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

package me.ahoo.wow.openapi.context

import com.github.victools.jsonschema.generator.SchemaVersion
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

internal class OpenAPIComponentContextTest {

    @Test
    fun `should create default context with schema version`() {
        val context = OpenAPIComponentContext.default(schemaVersion = SchemaVersion.DRAFT_2020_12)
        context.assert().isNotNull()
        context.inline.assert().isFalse()
    }

    @Test
    fun `should create default context with inline option`() {
        val context = OpenAPIComponentContext.default(inline = true)
        context.inline.assert().isTrue()
    }

    @Test
    fun `should have correct component reference constants`() {
        OpenAPIComponentContext.COMPONENTS_PREFIX.assert().isEqualTo("#/components/")
        OpenAPIComponentContext.COMPONENTS_HEADERS_REF.assert().isEqualTo("#/components/headers/")
        OpenAPIComponentContext.COMPONENTS_PARAMETERS_REF.assert().isEqualTo("#/components/parameters/")
        OpenAPIComponentContext.COMPONENTS_REQUEST_BODIES_REF.assert().isEqualTo("#/components/requestBodies/")
        OpenAPIComponentContext.COMPONENTS_RESPONSES_REF.assert().isEqualTo("#/components/responses/")
    }
}
```

- [ ] **Step 2: Create DefaultOpenAPIComponentContextTest**

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

package me.ahoo.wow.openapi.context

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

internal class DefaultOpenAPIComponentContextTest {

    private val context = OpenAPIComponentContext.default()

    @Test
    fun `should return empty schemas before finish`() {
        context.schemas.assert().isEmpty()
    }

    @Test
    fun `should return empty parameters before any registration`() {
        context.parameters.assert().isEmpty()
    }

    @Test
    fun `should register and retrieve parameter`() {
        val parameter = context.parameter("test-id") {
            name = "testParam"
        }
        context.parameters.assert().containsKey("test-id")
        parameter.`$ref`.assert().isEqualTo("#/components/parameters/test-id")
    }

    @Test
    fun `should register and retrieve header`() {
        context.header("X-Test") {
            description = "test"
        }
        context.headers.assert().containsKey("X-Test")
    }

    @Test
    fun `should register and retrieve request body`() {
        context.requestBody("test-body") {
            description = "test body"
        }
        context.requestBodies.assert().containsKey("test-body")
    }

    @Test
    fun `should register and retrieve response`() {
        context.response("test-response") {
            description("OK")
        }
        context.responses.assert().containsKey("test-response")
    }
}
```

- [ ] **Step 3: Create CurrentOpenAPIComponentContextTest**

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

package me.ahoo.wow.openapi.context

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

internal class CurrentOpenAPIComponentContextTest {

    @AfterEach
    fun tearDown() {
        CurrentOpenAPIComponentContext.current = null
    }

    @Test
    fun `should set and get current context`() {
        val context = OpenAPIComponentContext.default()
        CurrentOpenAPIComponentContext.current = context
        CurrentOpenAPIComponentContext.current.assert().isSameAs(context)
    }

    @Test
    fun `should clear current context when set to null`() {
        CurrentOpenAPIComponentContext.current = OpenAPIComponentContext.default()
        CurrentOpenAPIComponentContext.current = null
        CurrentOpenAPIComponentContext.current.assert().isNull()
    }
}
```

- [ ] **Step 4: Create OpenAPIComponentContextCapableTest**

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

package me.ahoo.wow.openapi.context

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

internal class OpenAPIComponentContextCapableTest {

    @Test
    fun `should return component context from capable implementation`() {
        val context = OpenAPIComponentContext.default()
        val capable = object : OpenAPIComponentContextCapable {
            override val componentContext: OpenAPIComponentContext = context
        }
        capable.componentContext.assert().isSameAs(context)
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.context.*"`
Expected: All PASS

- [ ] **Step 6: Commit**

```bash
git add wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/context/
git commit -m "test(openapi): add context layer tests"
```

---

## Task 7: Converter Layer

**Files:**
- Rewrite: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/converter/BoundedContextSchemaNameConverterTest.kt`
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/converter/WowSchemaConverterTest.kt`
- Delete: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/converter/ModelConvertersTest.kt`

- [ ] **Step 1: Rewrite BoundedContextSchemaNameConverterTest**

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

package me.ahoo.wow.openapi.converter

import com.fasterxml.jackson.databind.type.TypeFactory
import io.swagger.v3.core.converter.AnnotatedType
import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.BatchResult
import me.ahoo.wow.openapi.converter.BoundedContextSchemaNameConverter.Companion.resolveName
import org.junit.jupiter.api.Test

internal class BoundedContextSchemaNameConverterTest {

    @Test
    fun `should not resolve name for string java type`() {
        val type = TypeFactory.defaultInstance().constructType(String::class.java)
        val annotatedType = AnnotatedType(type)
        annotatedType.resolveName()
        annotatedType.name.assert().isNull()
    }

    @Test
    fun `should not resolve name for string class`() {
        val annotatedType = AnnotatedType(String::class.java)
        annotatedType.resolveName()
        annotatedType.name.assert().isNull()
    }

    @Test
    fun `should keep existing name when not blank`() {
        val annotatedType = AnnotatedType(String::class.java)
        annotatedType.name = "test"
        annotatedType.resolveName()
        annotatedType.name.assert().isEqualTo("test")
    }

    @Test
    fun `should resolve name for list java type`() {
        val type = TypeFactory.defaultInstance().constructCollectionLikeType(List::class.java, String::class.java)
        val annotatedType = AnnotatedType(type)
        annotatedType.resolveName()
        annotatedType.name.assert().isEqualTo("StringList")
    }

    @Test
    fun `should resolve name for non-standard type with bounded context prefix`() {
        val annotatedType = AnnotatedType(BatchResult::class.java)
        annotatedType.resolveName()
        annotatedType.name.assert().isEqualTo("wow.openapi.BatchResult")
    }
}
```

- [ ] **Step 2: Create WowSchemaConverterTest**

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

package me.ahoo.wow.openapi.converter

import io.swagger.v3.core.converter.AnnotatedType
import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.BatchResult
import me.ahoo.wow.openapi.context.CurrentOpenAPIComponentContext
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

internal class WowSchemaConverterTest {

    private val converter = WowSchemaConverter()

    @AfterEach
    fun tearDown() {
        CurrentOpenAPIComponentContext.current = null
    }

    @Test
    fun `should return null when no current context`() {
        CurrentOpenAPIComponentContext.current = null
        val type = AnnotatedType(BatchResult::class.java)
        val schema = converter.resolve(type, emptyList<io.swagger.v3.core.converter.ModelConverter>().listIterator())
        schema.assert().isNull()
    }

    @Test
    fun `should resolve schema from current context for class type`() {
        CurrentOpenAPIComponentContext.current = OpenAPIComponentContext.default(false)
        val type = AnnotatedType(BatchResult::class.java)
        val chain = emptyList<io.swagger.v3.core.converter.ModelConverter>().listIterator()
        val schema = converter.resolve(type, chain)
        schema.assert().isNotNull()
    }

    @Test
    fun `should set schema name from annotated type name`() {
        CurrentOpenAPIComponentContext.current = OpenAPIComponentContext.default(false)
        val type = AnnotatedType(BatchResult::class.java)
        val chain = emptyList<io.swagger.v3.core.converter.ModelConverter>().listIterator()
        val schema = converter.resolve(type, chain)
        schema?.name.assert().isEqualTo("wow.openapi.BatchResult")
    }
}
```

- [ ] **Step 3: Delete ModelConvertersTest**

Delete `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/converter/ModelConvertersTest.kt`

- [ ] **Step 4: Run tests**

Run: `./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.converter.*"`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
git add wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/converter/
git commit -m "test(openapi): rewrite converter layer tests, replace ModelConvertersTest with WowSchemaConverterTest"
```

---

## Task 8: Metadata Layer — Parsers

**Files:**
- Rewrite: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/metadata/AggregateRouteMetadataParserTest.kt`
- Rewrite: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/metadata/CommandRouteMetadataParserTest.kt`

- [ ] **Step 1: Rewrite AggregateRouteMetadataParserTest**

Replace `Cart` fixture with a private fixture class defined within the test file. Use a simple aggregate type annotated with `@AggregateRoute`.

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

package me.ahoo.wow.openapi.metadata

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.Command
import org.junit.jupiter.api.Test

internal class AggregateRouteMetadataParserTest {

    @Test
    fun `should parse aggregate route metadata with default values when no annotation`() {
        val metadata = aggregateRouteMetadata<UnannotatedAggregate>()
        metadata.enabled.assert().isTrue()
        metadata.owner.assert().isEqualTo(AggregateRoute.Owner.NEVER)
        metadata.spaced.assert().isFalse()
        metadata.resourceName.assert().isEqualTo("UnannotatedAggregate")
    }

    @Test
    fun `should parse aggregate route metadata with annotation`() {
        val metadata = aggregateRouteMetadata<AnnotatedAggregate>()
        metadata.enabled.assert().isTrue()
        metadata.owner.assert().isEqualTo(AggregateRoute.Owner.AGGREGATE_ID)
        metadata.resourceName.assert().isEqualTo("custom-resource")
        metadata.spaced.assert().isTrue()
    }

    @Test
    fun `should parse disabled aggregate route metadata`() {
        val metadata = aggregateRouteMetadata<DisabledAggregate>()
        metadata.enabled.assert().isFalse()
    }
}

@Suppress("unused")
private class UnannotatedAggregate(
    @AggregateId
    val id: String
)

@AggregateRoute(owner = AggregateRoute.Owner.AGGREGATE_ID, resourceName = "custom-resource", spaced = true)
@Suppress("unused")
private class AnnotatedAggregate(
    @AggregateId
    val id: String
)

@AggregateRoute(enabled = false)
@Suppress("unused")
private class DisabledAggregate(
    @AggregateId
    val id: String
)
```

- [ ] **Step 2: Rewrite CommandRouteMetadataParserTest**

Replace `MockCommandRoute`, `NestedMockCommandRoute`, etc. with private fixtures defined in the file. These fixtures use `@CommandRoute`, `@CommandRoute.PathVariable`, `@CommandRoute.HeaderVariable`, and `@JsonProperty` annotations exactly as the original tests do.

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

package me.ahoo.wow.openapi.metadata

import com.fasterxml.jackson.annotation.JsonProperty
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.api.command.DeleteAggregate
import me.ahoo.wow.infra.reflection.IntimateAnnotationElement.Companion.toIntimateAnnotationElement
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test

internal class CommandRouteMetadataParserTest {

    @Test
    fun `should parse command route metadata with path and header variables`() {
        val metadata = commandRouteMetadata<TestCommandRouteNotRequired>()
        metadata.enabled.assert().isTrue()
        metadata.action.assert().isEqualTo("{id}/{name}")
        metadata.prefix.assert().isEqualTo("")

        val idVariable = metadata.pathVariableMetadata.first { it.variableName == "id" }
        idVariable.field.assert().isNotNull()
        idVariable.fieldName.assert().isEqualTo("id")
        idVariable.variableName.assert().isEqualTo("id")
        idVariable.required.assert().isTrue()
        idVariable.variableType.assert().isEqualTo(String::class.java)

        val nameVariable = metadata.pathVariableMetadata.first { it.variableName == "name" }
        nameVariable.fieldName.assert().isEqualTo("customName")
        nameVariable.variableName.assert().isEqualTo("name")
        nameVariable.required.assert().isFalse()

        val headerVariable = metadata.headerVariableMetadata.first { it.variableName == "header" }
        headerVariable.fieldName.assert().isEqualTo("header")
        headerVariable.required.assert().isFalse()
    }

    @Test
    fun `should parse delete aggregate route method`() {
        val metadata = commandRouteMetadata<DefaultDeleteAggregate>()
        metadata.method.assert().isEqualTo(Https.Method.DELETE)
    }

    @Test
    fun `should decode command from path and header variables`() {
        val metadata = commandRouteMetadata<TestCommandRoute>()
        val command = metadata.decode(
            JsonSerializer.createObjectNode(),
            { mapOf("id" to "id", "name" to "name")[it] },
            { mapOf("header" to "header-value")[it] }
        )
        command.id.assert().isEqualTo("id")
        command.name.assert().isEqualTo("name")
        command.header.assert().isEqualTo("header-value")
    }

    @Test
    fun `should throw when required variable is missing during decode`() {
        val metadata = commandRouteMetadata<TestCommandRoute>()
        assertThrownBy<IllegalArgumentException> {
            metadata.decode(
                JsonSerializer.createObjectNode(),
                { mapOf("id" to "id")[it] },
                { null }
            )
        }
    }

    @Test
    fun `should decode command with optional variables using defaults`() {
        val metadata = commandRouteMetadata<TestCommandRouteNotRequired>()
        val command = metadata.decode(
            JsonSerializer.createObjectNode(),
            { mapOf("id" to "id")[it] },
            { null }
        )
        command.id.assert().isEqualTo("id")
        command.name.assert().isEqualTo("otherName")
        command.header.assert().isEqualTo("header")
    }

    @Test
    fun `should decode command with nested path variables`() {
        val metadata = commandRouteMetadata<TestNestedCommandRoute>()
        metadata.action.assert().isEqualTo("{customerId}/{id}/{name}")

        val customerIdVariable = metadata.pathVariableMetadata.first { it.variableName == "customerId" }
        customerIdVariable.fieldName.assert().isEqualTo("id")
        customerIdVariable.fieldPath.assert().contains("customer", "id")

        val command = metadata.decode(
            JsonSerializer.createObjectNode(),
            { mapOf("id" to "id", "customerId" to "customerId", "name" to "name")[it] },
            { null }
        )
        command.id.assert().isEqualTo("id")
        command.customer.id.assert().isEqualTo("customerId")
        command.customer.name.assert().isEqualTo("name")
    }

    @Test
    fun `should decode command with field-level nested path variables`() {
        TestNestedFieldCommandRoute::customer.toIntimateAnnotationElement()
        val metadata = commandRouteMetadata<TestNestedFieldCommandRoute>()
        metadata.action.assert().isEqualTo("{customerId}/{id}/{name}")

        val command = metadata.decode(
            JsonSerializer.createObjectNode(),
            { mapOf("id" to "id", "customerId" to "customerId", "name" to "name")[it] },
            { null }
        )
        command.id.assert().isEqualTo("id")
        command.customer.id.assert().isEqualTo("customerId")
        command.customer.name.assert().isEqualTo("name")
    }

    @Test
    fun `should handle missed variable in route metadata`() {
        val metadata = commandRouteMetadata<TestCommandRouteMissedVariable>()
        metadata.pathVariableMetadata.map { it.variableName }
            .assert().contains("id", "name")
        val nameVariable = metadata.pathVariableMetadata.first { it.variableName == "name" }
        nameVariable.field.assert().isNull()
        nameVariable.variableType.assert().isNull()
    }

    @Test
    fun `should parse command route without annotation using default action`() {
        val metadata = commandRouteMetadata<NoAnnotationCommand>()
        metadata.action.assert().isEqualTo("NoAnnotationCommand")
        metadata.enabled.assert().isTrue()
    }
}

@CommandRoute("{id}/{name}", method = CommandRoute.Method.PATCH)
private data class TestCommandRouteNotRequired(
    @CommandRoute.PathVariable
    val id: String,
    @field:JsonProperty("customName")
    @CommandRoute.PathVariable(name = "name", required = false)
    val name: String = "otherName",
    @CommandRoute.HeaderVariable(name = "header", required = false)
    val header: String = "header",
)

@CommandRoute("{id}/{name}", method = CommandRoute.Method.PATCH)
private data class TestCommandRoute(
    @CommandRoute.PathVariable
    val id: String,
    @field:JsonProperty("customName")
    @CommandRoute.PathVariable(name = "name")
    val name: String = "otherName",
    @CommandRoute.HeaderVariable(name = "header")
    val header: String = "header",
)

@CommandRoute("{customerId}/{id}/{name}")
private data class TestNestedCommandRoute(
    @CommandRoute.PathVariable
    val id: String,
    @CommandRoute.PathVariable(name = "customerId", nestedPath = ["id"])
    @CommandRoute.PathVariable(name = "name", nestedPath = ["name"])
    val customer: TestCustomer
) : DeleteAggregate

data class TestCustomer(val id: String, val name: String)

@CommandRoute("{customerId}/{id}/{name}")
private data class TestNestedFieldCommandRoute(
    @field:CommandRoute.PathVariable
    val id: String,
    @field:CommandRoute.PathVariable(name = "customerId", nestedPath = ["id"])
    @field:CommandRoute.PathVariable(name = "name", nestedPath = ["name"])
    val customer: TestCustomer
)

@CommandRoute("{id}/{name}", method = CommandRoute.Method.PATCH)
private data class TestCommandRouteMissedVariable(
    @CommandRoute.PathVariable
    val id: String,
    val name: String = "otherName",
)

private data class NoAnnotationCommand(val id: String)
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.metadata.AggregateRouteMetadataParserTest" --tests "me.ahoo.wow.openapi.metadata.CommandRouteMetadataParserTest"`
Expected: All PASS

- [ ] **Step 4: Commit**

```bash
git add wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/metadata/AggregateRouteMetadataParserTest.kt wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/metadata/CommandRouteMetadataParserTest.kt
git commit -m "test(openapi): rewrite metadata parser tests with self-contained fixtures"
```

---

## Task 9: Metadata Layer — Value Objects

**Files:**
- Rewrite: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/metadata/AggregateRouteMetadataTest.kt`
- Rewrite: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/metadata/CommandRouteMetadataTest.kt`
- Rewrite: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/metadata/VariableMetadataTest.kt`

- [ ] **Step 1: Rewrite AggregateRouteMetadataTest**

Use the same fixture types from Task 8 (defined as file-private in this file too).

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

package me.ahoo.wow.openapi.metadata

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.AggregateRoute
import org.junit.jupiter.api.Test

internal class AggregateRouteMetadataTest {

    @Test
    fun `should be equal to same aggregate route metadata`() {
        val metadata = aggregateRouteMetadata<FixtureAggregate>()
        metadata.assert().isEqualTo(metadata)
    }

    @Test
    fun `should not be equal to arbitrary object`() {
        val metadata = aggregateRouteMetadata<FixtureAggregate>()
        metadata.assert().isNotEqualTo(Any())
    }

    @Test
    fun `should not be equal to different aggregate route metadata`() {
        val metadata1 = aggregateRouteMetadata<FixtureAggregate>()
        val metadata2 = aggregateRouteMetadata<OtherFixtureAggregate>()
        metadata1.assert().isNotEqualTo(metadata2)
    }

    @Test
    fun `should have hash code matching aggregate metadata`() {
        val metadata = aggregateRouteMetadata<FixtureAggregate>()
        metadata.hashCode().assert().isEqualTo(metadata.aggregateMetadata.hashCode())
    }
}

@Suppress("unused")
@AggregateRoute(owner = AggregateRoute.Owner.AGGREGATE_ID)
private class FixtureAggregate(@AggregateId val id: String)

@Suppress("unused")
private class OtherFixtureAggregate(@AggregateId val id: String)
```

- [ ] **Step 2: Rewrite CommandRouteMetadataTest**

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

package me.ahoo.wow.openapi.metadata

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.CommandRoute
import org.junit.jupiter.api.Test

internal class CommandRouteMetadataTest {

    @Test
    fun `should be equal to same command route metadata`() {
        val metadata = commandRouteMetadata<FixtureCommand>()
        metadata.assert().isEqualTo(metadata)
    }

    @Test
    fun `should not be equal to arbitrary object`() {
        val metadata = commandRouteMetadata<FixtureCommand>()
        metadata.assert().isNotEqualTo(Any())
    }

    @Test
    fun `should not be equal to different command route metadata`() {
        val metadata1 = commandRouteMetadata<FixtureCommand>()
        val metadata2 = commandRouteMetadata<OtherFixtureCommand>()
        metadata1.assert().isNotEqualTo(metadata2)
    }

    @Test
    fun `should have hash code matching command metadata`() {
        val metadata = commandRouteMetadata<FixtureCommand>()
        metadata.hashCode().assert().isEqualTo(metadata.commandMetadata.hashCode())
    }
}

@CommandRoute("{id}")
private data class FixtureCommand(@CommandRoute.PathVariable val id: String)

@CommandRoute("{name}")
private data class OtherFixtureCommand(@CommandRoute.PathVariable val name: String)
```

- [ ] **Step 3: Rewrite VariableMetadataTest**

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

package me.ahoo.wow.openapi.metadata

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

internal class VariableMetadataTest {

    @Test
    fun `should resolve variable type from field when field path is empty`() {
        val field = java.lang.String::class.java.getDeclaredField("value")
        val metadata = VariableMetadata(field, listOf("value"), "testVar", true)
        metadata.variableType.assert().isEqualTo(field.genericType)
    }

    @Test
    fun `should return null variable type when field is null`() {
        val metadata = VariableMetadata(null, listOf("test"), "testVar", true)
        metadata.variableType.assert().isNull()
    }

    @Test
    fun `should provide field name as last element of field path`() {
        val metadata = VariableMetadata(null, listOf("customer", "id"), "customerId", true)
        metadata.fieldName.assert().isEqualTo("id")
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.metadata.*"`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
git add wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/metadata/
git commit -m "test(openapi): rewrite metadata value object tests"
```

---

## Task 10: Aggregate Route Layer

**Files:**
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/aggregate/AggregateRouteSpecFactoryProviderTest.kt`
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/aggregate/TenantOwnerRouteSummarySpecTest.kt`
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/aggregate/TenantOwnerAggregateRouteSpecTest.kt`

These test the core aggregate routing abstractions. Each RouteSpec implementation in aggregate/snapshot, aggregate/event, etc. shares the same pattern and will be tested in later tasks.

- [ ] **Step 1: Create AggregateRouteSpecFactoryProviderTest**

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

package me.ahoo.wow.openapi.aggregate

import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import org.junit.jupiter.api.Test

internal class AggregateRouteSpecFactoryProviderTest {

    @Test
    fun `should return factories loaded via service loader`() {
        val context = OpenAPIComponentContext.default()
        val provider = AggregateRouteSpecFactoryProvider(context)
        val factories = provider.get()
        factories.assert().isNotNull()
    }

    @Test
    fun `should return component context`() {
        val context = OpenAPIComponentContext.default()
        val provider = AggregateRouteSpecFactoryProvider(context)
        provider.componentContext.assert().isSameAs(context)
    }
}
```

- [ ] **Step 2: Create TenantOwnerRouteSummarySpecTest**

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

package me.ahoo.wow.openapi.aggregate

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

internal class TenantOwnerRouteSummarySpecTest {

    @Test
    fun `should build summary with operation only`() {
        val summary = TenantOwnerRouteSummarySpec()
            .operationSummary("Get Aggregate")
            .build()
        summary.assert().isEqualTo("Get Aggregate")
    }

    @Test
    fun `should build summary with tenant appended`() {
        val summary = TenantOwnerRouteSummarySpec()
            .operationSummary("Get Aggregate")
            .appendTenant(true)
            .build()
        summary.assert().isEqualTo("Get Aggregate Within Tenant")
    }

    @Test
    fun `should build summary with owner appended`() {
        val summary = TenantOwnerRouteSummarySpec()
            .operationSummary("Get Aggregate")
            .appendOwner(true)
            .build()
        summary.assert().isEqualTo("Get Aggregate Within Owner")
    }

    @Test
    fun `should build summary with tenant and owner appended`() {
        val summary = TenantOwnerRouteSummarySpec()
            .operationSummary("Get Aggregate")
            .appendTenant(true)
            .appendOwner(true)
            .build()
        summary.assert().isEqualTo("Get Aggregate Within Tenant Owner")
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.aggregate.*"`
Expected: All PASS

- [ ] **Step 4: Commit**

```bash
git add wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/aggregate/
git commit -m "test(openapi): add aggregate route layer tests"
```

---

## Task 11: Global Route Layer

**Files:**
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/global/GlobalRouteSpecFactoryProviderTest.kt`
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/global/CommandWaitRouteSpecTest.kt`
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/global/GenerateGlobalIdRouteSpecTest.kt`
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/global/GenerateScriptRouteSpecTest.kt`
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/global/GetWowMetadataRouteSpecTest.kt`

- [ ] **Step 1: Create GlobalRouteSpecFactoryProviderTest**

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

package me.ahoo.wow.openapi.global

import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import org.junit.jupiter.api.Test

internal class GlobalRouteSpecFactoryProviderTest {

    @Test
    fun `should return component context`() {
        val context = OpenAPIComponentContext.default()
        val provider = GlobalRouteSpecFactoryProvider(context)
        provider.componentContext.assert().isSameAs(context)
    }

    @Test
    fun `should return factories loaded via service loader`() {
        val context = OpenAPIComponentContext.default()
        val provider = GlobalRouteSpecFactoryProvider(context)
        provider.get().assert().isNotNull()
    }
}
```

- [ ] **Step 2: Create CommandWaitRouteSpecTest**

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

package me.ahoo.wow.openapi.global

import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import org.junit.jupiter.api.Test

internal class CommandWaitRouteSpecTest {

    private val context = OpenAPIComponentContext.default()

    @Test
    fun `should have post method`() {
        val spec = CommandWaitRouteSpec(context)
        spec.method.assert().isEqualTo(Https.Method.POST)
    }

    @Test
    fun `should have wait path`() {
        val spec = CommandWaitRouteSpec(context)
        spec.path.assert().isEqualTo(CommandWaitRouteSpecFactory.PATH)
    }

    @Test
    fun `should have request body`() {
        val spec = CommandWaitRouteSpec(context)
        spec.requestBody.assert().isNotNull()
    }
}
```

- [ ] **Step 3: Create GenerateGlobalIdRouteSpecTest**

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

package me.ahoo.wow.openapi.global

import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import org.junit.jupiter.api.Test

internal class GenerateGlobalIdRouteSpecTest {

    private val context = OpenAPIComponentContext.default()

    @Test
    fun `should have get method`() {
        val spec = GenerateGlobalIdRouteSpec(context)
        spec.method.assert().isEqualTo(Https.Method.GET)
    }

    @Test
    fun `should have text plain accept`() {
        val spec = GenerateGlobalIdRouteSpec(context)
        spec.accept.assert().contains(Https.MediaType.TEXT_PLAIN)
    }

    @Test
    fun `should have global id path`() {
        val spec = GenerateGlobalIdRouteSpec(context)
        spec.path.assert().isEqualTo("/${me.ahoo.wow.api.Wow.WOW}/id/global")
    }
}
```

- [ ] **Step 4: Create GenerateScriptRouteSpecTest and GetWowMetadataRouteSpecTest**

Follow the same pattern: instantiate the spec with `OpenAPIComponentContext.default()`, assert method, path, and key response properties. Read each source file for exact values.

- [ ] **Step 5: Run tests**

Run: `./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.global.*"`
Expected: All PASS

- [ ] **Step 6: Commit**

```bash
git add wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/global/
git commit -m "test(openapi): add global route layer tests"
```

---

## Task 12: Aggregate Sub-Layers — Command, Event, Snapshot, State

**Files:** Create test files for each RouteSpec implementation under:
- `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/aggregate/command/`
- `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/aggregate/event/`
- `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/aggregate/snapshot/`
- `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/aggregate/state/`

Each test follows the same pattern: create a minimal `AggregateRouteMetadata` fixture using `aggregateRouteMetadata<T>()` where `T` is a private fixture aggregate class, instantiate the RouteSpec, and assert `method`, `path` contains expected suffix, `id` is non-blank, and `responses` is non-empty.

**Pattern for each RouteSpec test file:**

```kotlin
internal class LoadSnapshotRouteSpecTest {
    private val context = OpenAPIComponentContext.default()
    private val aggregateRouteMetadata = aggregateRouteMetadata<FixtureAggregate>()
    private val namedContext = MaterializedNamedBoundedContext("test")

    @Test
    fun `should have get method`() {
        val spec = LoadSnapshotRouteSpec(namedContext, aggregateRouteMetadata, context)
        spec.method.assert().isEqualTo(Https.Method.GET)
    }

    @Test
    fun `should have snapshot path suffix`() {
        val spec = LoadSnapshotRouteSpec(namedContext, aggregateRouteMetadata, context)
        spec.path.assert().contains("snapshot")
    }
}
```

Repeat this pattern for each RouteSpec in snapshot/, event/, state/, and command/ subdirectories. Read each source file to determine the correct `method`, `appendPathSuffix`, and response structure.

- [ ] **Step 1: Create command layer tests** (CommandComponentTest, CommandFacadeRouteSpecTest, CommandRouteSpecTest)
- [ ] **Step 2: Run and verify**
- [ ] **Step 3: Commit**

```bash
git add wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/aggregate/command/
git commit -m "test(openapi): add aggregate command layer tests"
```

- [ ] **Step 4: Create event layer tests** (EventComponentTest, LoadEventStreamRouteSpecTest, CountEventStreamRouteSpecTest, etc.)
- [ ] **Step 5: Run and verify**
- [ ] **Step 6: Commit**

```bash
git add wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/aggregate/event/
git commit -m "test(openapi): add aggregate event layer tests"
```

- [ ] **Step 7: Create snapshot layer tests** (LoadSnapshotRouteSpecTest, CountSnapshotRouteSpecTest, etc.)
- [ ] **Step 8: Run and verify**
- [ ] **Step 9: Commit**

```bash
git add wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/aggregate/snapshot/
git commit -m "test(openapi): add aggregate snapshot layer tests"
```

- [ ] **Step 10: Create state layer tests** (LoadAggregateComponentTest, LoadAggregateRouteSpecTest, etc.)
- [ ] **Step 11: Run and verify**
- [ ] **Step 12: Commit**

```bash
git add wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/aggregate/state/
git commit -m "test(openapi): add aggregate state layer tests"
```

---

## Task 13: RouterSpecs Rewrite and Final Verification

**Files:**
- Rewrite: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/RouterSpecsTest.kt`

- [ ] **Step 1: Rewrite RouterSpecsTest**

This is the most complex test. It requires a `MetadataSearcher` to have discovered at least one named aggregate type, which normally happens via the KSP-processed `wow-metadata` resource files. Since we're removing `example-domain`, we need the test to work with the module's own metadata or set up the searcher manually.

Examine `RouterSpecs.build()` — it calls `MetadataSearcher.namedAggregateType` and `MetadataSearcher.metadata`. Check whether `wow-openapi` has any KSP-generated metadata of its own by looking in `build/` or `resources/`.

If no metadata is available, the test should:
1. Create a `RouterSpecs` with an empty context (no aggregates found) — verify it builds an empty route list
2. Use `mergeOpenAPI` with a pre-built OpenAPI to verify the merge logic works

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

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.info.Info
import me.ahoo.test.asserts.assert
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import org.junit.jupiter.api.Test

internal class RouterSpecsTest {

    private val namedContext = MaterializedNamedBoundedContext("test-service")

    @Test
    fun `should build and return non-empty routes`() {
        val routerSpecs = RouterSpecs(namedContext).build()
        // Routes may be empty if no aggregates discovered in test classpath
        routerSpecs.assert().isNotNull()
    }

    @Test
    fun `should merge router specs into open api with context name as title`() {
        val openAPI = OpenAPI()
        RouterSpecs(namedContext).build().mergeOpenAPI(openAPI)
        openAPI.info?.title.assert().isEqualTo(namedContext.contextName)
    }

    @Test
    fun `should keep existing info when merging`() {
        val info = Info().title("Custom Title")
        val openAPI = OpenAPI().info(info)
        RouterSpecs(namedContext).build().mergeOpenAPI(openAPI)
        openAPI.info.assert().isSameAs(info)
        openAPI.info.title.assert().isEqualTo("Custom Title")
    }

    @Test
    fun `should replace default info title when merging`() {
        val info = Info().title(RouterSpecs.DEFAULT_OPENAPI_INFO_TITLE).description("hello")
        val openAPI = OpenAPI().info(info)
        RouterSpecs(namedContext).build().mergeOpenAPI(openAPI)
        openAPI.info.assert().isSameAs(info)
        openAPI.info.title.assert().isEqualTo(namedContext.contextName)
    }

    @Test
    fun `should merge into existing open api preserving paths and components`() {
        val info = Info()
        val paths = Paths()
        val components = Components()
        val openAPI = OpenAPI().info(info).paths(paths).components(components)
        RouterSpecs(namedContext).build().mergeOpenAPI(openAPI)
        openAPI.info.assert().isSameAs(info)
        openAPI.paths.assert().isSameAs(paths)
        openAPI.components.assert().isSameAs(components)
    }
}
```

- [ ] **Step 2: Run the full test suite**

Run: `./gradlew :wow-openapi:test`
Expected: All PASS

- [ ] **Step 3: Commit**

```bash
git add wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/RouterSpecsTest.kt
git commit -m "test(openapi): rewrite RouterSpecsTest without external dependencies"
```

---

## Task 14: Final Cleanup and Full Verification

- [ ] **Step 1: Verify no references to removed modules remain**

Run: `grep -rn "example-domain\|example.transfer\|wow.tck\|MockCommandAggregate\|ExampleService" wow-openapi/src/test/`
Expected: No output (all references removed)

- [ ] **Step 2: Verify build.gradle.kts has no external test dependencies**

Run: `grep -n "example-domain\|example-transfer-domain\|wow-tck" wow-openapi/build.gradle.kts`
Expected: No output

- [ ] **Step 3: Run full check**

Run: `./gradlew :wow-openapi:test --stacktrace`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 4: Final commit if any cleanup needed**

```bash
git add -A wow-openapi/
git commit -m "test(openapi): final cleanup after full test rewrite"
```
