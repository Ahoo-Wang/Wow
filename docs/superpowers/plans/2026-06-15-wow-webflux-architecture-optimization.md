# wow-webflux 架构优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `wow-webflux` 重构成职责清晰、Reactor-first、外部 HTTP API 兼容的 WebFlux adapter 层。

**Architecture:** 先建立 response/error strategy 作为新边界，再迁移 `Responses.kt` 的隐式逻辑；随后引入 request context 与 operation policy，把 batch、tracing、wait notify 等决策从 handler 中抽出；最后拆分 route-family module，瘦身 `WebFluxAutoConfiguration`，并清理过渡 facade。

**Tech Stack:** Kotlin 2.3、Spring WebFlux functional routing、Reactor `Mono`/`Flux`、Spring Boot auto-configuration、JUnit Jupiter、MockK、Reactor Test、JMH。

---

## 范围检查

这个设计包含多个架构单元，但它们不是互相独立的子系统，而是一条有依赖顺序的 adapter 迁移链：

1. response/error strategy 是后续 streaming、context 和 policy 的共同出口；
2. request context 是 handler 迁移的共同入口；
3. operation policy 依赖 response/context 边界稳定；
4. route module 拆分应在行为迁移稳定后进行，避免先搬运旧结构。

因此本计划作为一个实施计划推进，但每个 task 都必须独立提交并保留可运行状态。

## 文件结构

### 新增文件

- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/exception/WebFluxErrorStrategy.kt`
  统一 `Throwable -> ErrorInfo -> HTTP/SSE/stream error` 映射。
- `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/exception/WebFluxErrorStrategyTest.kt`
  覆盖错误 response 和 facade 委托。
- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/response/WebFluxResponseStrategy.kt`
  定义 Reactor-first response strategy 接口和默认实现。
- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/response/StreamingJsonArray.kt`
  封装 `Flux<T>` 到 streaming JSON array 的编码逻辑。
- `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/response/WebFluxResponseStrategyTest.kt`
  覆盖 single JSON、streaming JSON array、SSE 与 error-before-write。
- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/context/WowWebRequestContext.kt`
  集中 HTTP 派生事实。
- `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/context/WowWebRequestContextTest.kt`
  覆盖 tenant、owner、aggregateId、requestId、SSE 和 wait timeout 解析。
- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/policy/BatchExecutionPolicy.kt`
  表达 batch concurrency、prefetch 和 failure aggregation。
- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/policy/TracingPolicy.kt`
  表达 aggregate tracing 的 head/tail/limit/window 语义。
- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/policy/CommandWaitPolicy.kt`
  表达 command wait timeout 与 SSE/non-SSE 响应选择。
- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/wait/RemoteWaitNotifyPolicy.kt`
  表达 remote wait notify 的 retry、scheduler 和 endpoint 策略。
- `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/policy/BatchExecutionPolicyTest.kt`
  覆盖默认串行行为和可配置并发。
- `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/policy/TracingPolicyTest.kt`
  覆盖 full history、tail limit 和非法版本范围。
- `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/route/WebFluxRouteModule.kt`
  route-family module SPI。
- `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/route/CommandRouteModule.kt`
  command/wait route factory grouping。
- `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/route/QueryRouteModule.kt`
  query/event-stream query route factory grouping。
- `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/route/SnapshotRouteModule.kt`
  snapshot route factory grouping。
- `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/route/StateRouteModule.kt`
  aggregate state/tracing route factory grouping。
- `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/route/EventRouteModule.kt`
  event/compensation route factory grouping。
- `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/route/GlobalRouteModule.kt`
  global route factory grouping。

### 修改文件

- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/Responses.kt`
  过渡为薄 facade，最终只委托 `WebFluxResponseStrategy`。
- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/command/CommandResponses.kt`
  过渡为薄 facade，委托 command response strategy。
- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/exception/RequestExceptionHandler.kt`
  改为委托 `WebFluxErrorStrategy`。
- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/exception/GlobalExceptionHandler.kt`
  改为复用同一个 `WebFluxErrorStrategy`。
- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionRegistrar.kt`
  改为启动期不可变索引和明确重复注册诊断。
- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouterFunctionBuilder.kt`
  缺失 factory 时输出 path/method/spec class。
- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingHandlerFunction.kt`
  使用 `TracingPolicy` 和 streaming response。
- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/BatchRegenerateSnapshotHandlerFunction.kt`
  使用 `BatchExecutionPolicy`。
- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/event/state/ResendStateEventHandler.kt`
  使用 `BatchExecutionPolicy`。
- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/wait/WebClientCommandWaitNotifier.kt`
  使用 `RemoteWaitNotifyPolicy`。
- `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxProperties.kt`
  增加 response、batch、tracing、command wait、remote notify 配置。
- `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfiguration.kt`
  提供 strategy/policy bean，收集 route module，逐步移除平铺 factory 清单。
- `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/webflux/WebFluxResponseBenchmark.kt`
  增加 streaming JSON array benchmark。
- `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/webflux/AggregateTracingBenchmark.kt`
  对齐生产 tracing policy helper。

---

### Task 1: 建立统一 Error Strategy

**Files:**
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/exception/WebFluxErrorStrategy.kt`
- Create: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/exception/WebFluxErrorStrategyTest.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/exception/RequestExceptionHandler.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/exception/GlobalExceptionHandler.kt`
- Test: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/exception/DefaultRequestExceptionHandlerTest.kt`
- Test: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/exception/GlobalExceptionHandlerTest.kt`

- [ ] **Step 1: 写失败测试，证明 RequestExceptionHandler 与 GlobalExceptionHandler 共用同一映射**

Add to `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/exception/WebFluxErrorStrategyTest.kt`:

```kotlin
package me.ahoo.wow.webflux.exception

import me.ahoo.test.asserts.assert
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.openapi.CommonComponent.Header.ERROR_CODE
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.kotlin.test.test

class WebFluxErrorStrategyTest {
    @Test
    fun `should map throwable to functional server response`() {
        val request = MockServerRequest.builder()
            .method(org.springframework.http.HttpMethod.POST)
            .uri("/test")
            .build()

        DefaultWebFluxErrorStrategy.toServerResponse(request, IllegalArgumentException("bad"))
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.BAD_REQUEST)
                it.headers().contentType.assert().isEqualTo(MediaType.APPLICATION_JSON)
                it.headers().getFirst(ERROR_CODE).assert().isEqualTo(ErrorCodes.ILLEGAL_ARGUMENT)
            }
            .verifyComplete()
    }

    @Test
    fun `should write throwable to web exchange`() {
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/test").build()
        )

        DefaultWebFluxErrorStrategy.writeToExchange(exchange, IllegalArgumentException("bad"))
            .test()
            .verifyComplete()

        exchange.response.statusCode.assert().isEqualTo(HttpStatus.BAD_REQUEST)
        exchange.response.headers.contentType.assert().isEqualTo(MediaType.APPLICATION_JSON)
        exchange.response.headers.getFirst(ERROR_CODE).assert().isEqualTo(ErrorCodes.ILLEGAL_ARGUMENT)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
./gradlew :wow-webflux:test --tests "me.ahoo.wow.webflux.exception.WebFluxErrorStrategyTest"
```

Expected: FAIL，提示 `Unresolved reference: DefaultWebFluxErrorStrategy`。

- [ ] **Step 3: 新增 error strategy 实现**

Create `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/exception/WebFluxErrorStrategy.kt`:

```kotlin
package me.ahoo.wow.webflux.exception

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.exception.toErrorInfo
import me.ahoo.wow.openapi.CommonComponent
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.webflux.exception.ErrorHttpStatusMapping.toHttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

interface WebFluxErrorStrategy {
    fun toServerResponse(request: ServerRequest, throwable: Throwable): Mono<ServerResponse>
    fun writeToExchange(exchange: ServerWebExchange, throwable: Throwable): Mono<Void>
}

object DefaultWebFluxErrorStrategy : WebFluxErrorStrategy {
    private val log = KotlinLogging.logger {}

    override fun toServerResponse(request: ServerRequest, throwable: Throwable): Mono<ServerResponse> {
        log.warn(throwable) {
            "HTTP ${request.method()} ${request.uri()}"
        }
        val errorInfo = throwable.toErrorInfo()
        return ServerResponse.status(errorInfo.toHttpStatus())
            .contentType(MediaType.APPLICATION_JSON)
            .header(CommonComponent.Header.ERROR_CODE, errorInfo.errorCode)
            .bodyValue(errorInfo.toJsonString())
    }

    override fun writeToExchange(exchange: ServerWebExchange, throwable: Throwable): Mono<Void> {
        log.warn(throwable) {
            "HTTP ${exchange.request.method} ${exchange.request.uri}"
        }
        if (exchange.response.isCommitted) {
            return Mono.empty()
        }
        val errorInfo = throwable.toErrorInfo()
        val response = exchange.response
        response.statusCode = errorInfo.toHttpStatus()
        response.headers.contentType = MediaType.APPLICATION_JSON
        response.headers.set(CommonComponent.Header.ERROR_CODE, errorInfo.errorCode)
        val bytes = errorInfo.toJsonString().toByteArray()
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)))
    }
}
```

- [ ] **Step 4: 迁移 RequestExceptionHandler facade**

Replace `DefaultRequestExceptionHandler` body in `RequestExceptionHandler.kt` with:

```kotlin
object DefaultRequestExceptionHandler : RequestExceptionHandler {
    override fun handle(request: ServerRequest, throwable: Throwable): Mono<ServerResponse> {
        return DefaultWebFluxErrorStrategy.toServerResponse(request, throwable)
    }
}
```

- [ ] **Step 5: 迁移 GlobalExceptionHandler**

Replace `GlobalExceptionHandler.handle` body with:

```kotlin
override fun handle(exchange: ServerWebExchange, ex: Throwable): Mono<Void> {
    return DefaultWebFluxErrorStrategy.writeToExchange(exchange, ex)
}
```

Keep `getOrder()` unchanged.

- [ ] **Step 6: 运行 error tests**

Run:

```bash
./gradlew :wow-webflux:test --tests "me.ahoo.wow.webflux.exception.*"
```

Expected: PASS。

- [ ] **Step 7: Commit**

```bash
git add wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/exception \
  wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/exception
git commit -m "refactor(webflux): centralize error strategy"
```

---

### Task 2: 建立 Reactor-first Response Strategy

**Files:**
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/response/WebFluxResponseStrategy.kt`
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/response/StreamingJsonArray.kt`
- Create: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/response/WebFluxResponseStrategyTest.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/Responses.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/command/CommandResponses.kt`
- Test: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/ResponsesKtTest.kt`

- [ ] **Step 1: 写失败测试，锁定 streaming JSON array 响应**

Create `WebFluxResponseStrategyTest.kt`:

```kotlin
package me.ahoo.wow.webflux.route.response

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.codec.json.JacksonJsonEncoder
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.kotlin.test.test
import tools.jackson.databind.json.JsonMapper

class WebFluxResponseStrategyTest {
    @Test
    fun `should create streaming json array response for flux`() {
        val request = MockServerRequest.builder().build()

        DefaultWebFluxResponseStrategy
            .jsonArray(Flux.just(mapOf("value" to 1), mapOf("value" to 2)), request, DefaultRequestExceptionHandler)
            .test()
            .consumeNextWith {
                it.statusCode().is2xxSuccessful.assert().isTrue()
                it.headers().contentType.assert().isEqualTo(MediaType.APPLICATION_JSON)
                it.headers().getFirst(me.ahoo.wow.openapi.CommonComponent.Header.ERROR_CODE)
                    .assert().isEqualTo(ErrorInfo.SUCCEEDED)
            }
            .verifyComplete()
    }

    @Test
    fun `should keep sse response content type`() {
        val request = MockServerRequest.builder()
            .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
            .build()

        DefaultWebFluxResponseStrategy
            .jsonArray(Flux.just("a"), request, DefaultRequestExceptionHandler)
            .test()
            .consumeNextWith {
                it.headers().contentType.assert().isEqualTo(MediaType.TEXT_EVENT_STREAM)
            }
            .verifyComplete()
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
./gradlew :wow-webflux:test --tests "me.ahoo.wow.webflux.route.response.WebFluxResponseStrategyTest"
```

Expected: FAIL，提示 `Unresolved reference: DefaultWebFluxResponseStrategy`。

- [ ] **Step 3: 新增 StreamingJsonArrayResponse**

Create `StreamingJsonArray.kt`:

```kotlin
package me.ahoo.wow.webflux.route.response

import me.ahoo.wow.serialization.toJsonString
import org.springframework.core.ResolvableType
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferFactory
import org.springframework.http.MediaType
import org.springframework.http.codec.HttpMessageWriter
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class StreamingJsonArrayResponse<T : Any>(
    private val body: Flux<T>,
    private val headers: Map<String, String>
) : ServerResponse {
    override fun statusCode() = org.springframework.http.HttpStatus.OK
    override fun rawStatusCode() = statusCode().value()
    override fun headers() = org.springframework.http.HttpHeaders().also { target ->
        target.contentType = MediaType.APPLICATION_JSON
        headers.forEach { (name, value) -> target.set(name, value) }
    }
    override fun cookies() = org.springframework.util.LinkedMultiValueMap<String, org.springframework.http.ResponseCookie>()

    override fun writeTo(exchange: org.springframework.web.server.ServerWebExchange, context: ServerResponse.Context): Mono<Void> {
        val response = exchange.response
        response.statusCode = statusCode()
        response.headers.contentType = MediaType.APPLICATION_JSON
        headers.forEach { (name, value) -> response.headers.set(name, value) }
        val buffers = encode(response.bufferFactory())
        return response.writeWith(buffers)
    }

    private fun encode(bufferFactory: DataBufferFactory): Flux<DataBuffer> {
        val open = Flux.just(bufferFactory.wrap("[".toByteArray()))
        val close = Flux.just(bufferFactory.wrap("]".toByteArray()))
        val content = body.index().map { indexed ->
            val prefix = if (indexed.t1 == 0L) "" else ","
            bufferFactory.wrap((prefix + indexed.t2.toJsonString()).toByteArray())
        }
        return open.concatWith(content).concatWith(close)
    }
}
```

- [ ] **Step 4: 新增 WebFluxResponseStrategy**

Create `WebFluxResponseStrategy.kt`:

```kotlin
package me.ahoo.wow.webflux.route.response

import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.webflux.exception.ErrorHttpStatusMapping.toHttpStatus
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.StringServerSentEventType
import me.ahoo.wow.webflux.route.command.isSse
import me.ahoo.wow.webflux.route.errorResume
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface WebFluxResponseStrategy {
    fun singleJson(value: Mono<*>, request: ServerRequest, exceptionHandler: RequestExceptionHandler): Mono<ServerResponse>
    fun <T : Any> jsonArray(value: Flux<T>, request: ServerRequest, exceptionHandler: RequestExceptionHandler): Mono<ServerResponse>
    fun commandResult(value: Flux<CommandResult>, request: ServerRequest, exceptionHandler: RequestExceptionHandler): Mono<ServerResponse>
    fun sse(value: Flux<ServerSentEvent<String>>, request: ServerRequest, exceptionHandler: RequestExceptionHandler): Mono<ServerResponse>
}

object DefaultWebFluxResponseStrategy : WebFluxResponseStrategy {
    override fun singleJson(value: Mono<*>, request: ServerRequest, exceptionHandler: RequestExceptionHandler): Mono<ServerResponse> {
        return value.flatMap {
            if (it is ErrorInfo) {
                return@flatMap ServerResponse.status(it.toHttpStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(me.ahoo.wow.openapi.CommonComponent.Header.ERROR_CODE, it.errorCode)
                    .bodyValue(it.toJsonString())
            }
            ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(me.ahoo.wow.openapi.CommonComponent.Header.ERROR_CODE, ErrorInfo.SUCCEEDED)
                .bodyValue(it.toJsonString())
        }.onErrorResume {
            exceptionHandler.handle(request, it)
        }
    }

    override fun <T : Any> jsonArray(value: Flux<T>, request: ServerRequest, exceptionHandler: RequestExceptionHandler): Mono<ServerResponse> {
        if (request.isSse()) {
            val events = value.map {
                ServerSentEvent.builder<String>().data(it.toJsonString()).build()
            }
            return sse(events, request, exceptionHandler)
        }
        return Mono.just(
            StreamingJsonArrayResponse(
                value,
                mapOf(me.ahoo.wow.openapi.CommonComponent.Header.ERROR_CODE to ErrorInfo.SUCCEEDED)
            )
        )
    }

    override fun commandResult(value: Flux<CommandResult>, request: ServerRequest, exceptionHandler: RequestExceptionHandler): Mono<ServerResponse> {
        if (!request.isSse()) {
            return singleJson(value.next(), request, exceptionHandler)
        }
        val events = value.map {
            ServerSentEvent.builder<String>()
                .id(it.id)
                .event(it.stage.name)
                .data(it.toJsonString())
                .build()
        }
        return sse(events, request, exceptionHandler)
    }

    override fun sse(value: Flux<ServerSentEvent<String>>, request: ServerRequest, exceptionHandler: RequestExceptionHandler): Mono<ServerResponse> {
        return ServerResponse.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .header(me.ahoo.wow.openapi.CommonComponent.Header.ERROR_CODE, ErrorInfo.SUCCEEDED)
            .body(value.errorResume(request, exceptionHandler), StringServerSentEventType)
    }
}
```

- [ ] **Step 5: 迁移 facade**

Change `Responses.kt`:

```kotlin
fun Mono<*>.toServerResponse(
    request: ServerRequest,
    exceptionHandler: RequestExceptionHandler
): Mono<ServerResponse> {
    return DefaultWebFluxResponseStrategy.singleJson(this, request, exceptionHandler)
}

fun <T : Any> Flux<T>.toServerResponse(
    request: ServerRequest,
    exceptionHandler: RequestExceptionHandler
): Mono<ServerResponse> {
    return DefaultWebFluxResponseStrategy.jsonArray(this, request, exceptionHandler)
}
```

Change `CommandResponses.kt`:

```kotlin
fun Flux<CommandResult>.toCommandResponse(
    request: ServerRequest,
    exceptionHandler: RequestExceptionHandler
): Mono<ServerResponse> {
    return DefaultWebFluxResponseStrategy.commandResult(this, request, exceptionHandler)
}
```

- [ ] **Step 6: 运行 response tests**

Run:

```bash
./gradlew :wow-webflux:test --tests "me.ahoo.wow.webflux.route.ResponsesKtTest" \
  --tests "me.ahoo.wow.webflux.route.response.WebFluxResponseStrategyTest"
```

Expected: PASS。

- [ ] **Step 7: Commit**

```bash
git add wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route \
  wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route
git commit -m "refactor(webflux): introduce response strategy"
```

---

### Task 3: 增加 Response Benchmark 回归行

**Files:**
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/webflux/WebFluxResponseBenchmark.kt`
- Test: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/webflux/WebFluxResponseBenchmark.kt`

- [ ] **Step 1: 增加 streaming JSON array benchmark row**

Add to `WebFluxResponseBenchmark`:

```kotlin
@Benchmark
fun fluxJsonStreamingArrayResponse(blackhole: Blackhole) {
    val response = Flux.fromIterable(payloads)
        .toServerResponse(jsonRequest, DefaultRequestExceptionHandler)
        .block()
    response
        ?.writeTo(WebFluxBenchmarkSupport.jsonExchange(), WebFluxBenchmarkSupport.jsonResponseContext)
        ?.block()
    blackhole.consume(response)
}
```

- [ ] **Step 2: 如果 `WebFluxBenchmarkSupport` 没有 JSON exchange helper，补齐 helper**

Add these members to `WebFluxBenchmarkSupport`:

```kotlin
val jsonResponseContext = object : ServerResponse.Context {
    override fun messageWriters() = HandlerStrategies.withDefaults().messageWriters()
    override fun viewResolvers() = emptyList<org.springframework.web.reactive.result.view.ViewResolver>()
}

fun jsonExchange(): MockServerWebExchange {
    return MockServerWebExchange.from(MockServerHttpRequest.post("/").build())
}
```

Imports:

```kotlin
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerResponse
```

- [ ] **Step 3: 编译 JMH**

Run:

```bash
./gradlew :wow-benchmarks:compileJmhKotlin
```

Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```bash
git add wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/webflux
git commit -m "test(benchmarks): cover streaming webflux responses"
```

---

### Task 4: 引入 WowWebRequestContext

**Files:**
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/context/WowWebRequestContext.kt`
- Create: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/context/WowWebRequestContextTest.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/command/CommandHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingHandlerFunction.kt`
- Test: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/command/CommandHandlerFunctionTest.kt`
- Test: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingHandlerFunctionTest.kt`

- [ ] **Step 1: 写 context 测试**

Create `WowWebRequestContextTest.kt`:

```kotlin
package me.ahoo.wow.webflux.route.context

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Header.REQUEST_ID
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.reactive.function.server.MockServerRequest

class WowWebRequestContextTest {
    @Test
    fun `should extract tenant id aggregate id request id and sse mode`() {
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, "aggregate-id")
            .pathVariable(MessageRecords.TENANT_ID, TenantId.DEFAULT_TENANT_ID)
            .header(REQUEST_ID, "request-id")
            .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
            .build()

        val context = WowWebRequestContext.of(request, MOCK_AGGREGATE_METADATA)

        context.aggregateId.id.assert().isEqualTo("aggregate-id")
        context.aggregateId.tenantId.assert().isEqualTo(TenantId.DEFAULT_TENANT_ID)
        context.requestId.assert().isEqualTo("request-id")
        context.sse.assert().isTrue()
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
./gradlew :wow-webflux:test --tests "me.ahoo.wow.webflux.route.context.WowWebRequestContextTest"
```

Expected: FAIL，提示 `Unresolved reference: WowWebRequestContext`。

- [ ] **Step 3: 新增 context**

Create `WowWebRequestContext.kt`:

```kotlin
package me.ahoo.wow.webflux.route.context

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Header.REQUEST_ID
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.webflux.route.command.getTenantIdOrDefault
import me.ahoo.wow.webflux.route.command.isSse
import org.springframework.web.reactive.function.server.ServerRequest

data class WowWebRequestContext(
    val request: ServerRequest,
    val aggregateMetadata: AggregateMetadata<*, *>?,
    val aggregateId: AggregateId,
    val requestId: String?,
    val sse: Boolean
) {
    companion object {
        fun of(request: ServerRequest, aggregateMetadata: AggregateMetadata<*, *>): WowWebRequestContext {
            val tenantId = request.getTenantIdOrDefault(aggregateMetadata)
            val id = request.pathVariable(MessageRecords.ID)
            return WowWebRequestContext(
                request = request,
                aggregateMetadata = aggregateMetadata,
                aggregateId = aggregateMetadata.aggregateId(id = id, tenantId = tenantId),
                requestId = request.headers().firstHeader(REQUEST_ID),
                sse = request.isSse()
            )
        }
    }
}
```

- [ ] **Step 4: 迁移 AggregateTracingHandlerFunction 使用 context**

Replace tenant/id extraction in `AggregateTracingHandlerFunction.handle` with:

```kotlin
val context = WowWebRequestContext.of(request, aggregateMetadata)
return eventStore
    .load(
        aggregateId = context.aggregateId,
    ).collectList()
    .map {
        aggregateMetadata.state.trace(stateAggregateFactory, it)
    }.toServerResponse(request, exceptionHandler)
```

Add import:

```kotlin
import me.ahoo.wow.webflux.route.context.WowWebRequestContext
```

- [ ] **Step 5: 运行 context 和 tracing tests**

Run:

```bash
./gradlew :wow-webflux:test --tests "me.ahoo.wow.webflux.route.context.WowWebRequestContextTest" \
  --tests "me.ahoo.wow.webflux.route.state.AggregateTracingHandlerFunctionTest"
```

Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/context \
  wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/context \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingHandlerFunction.kt
git commit -m "refactor(webflux): add request context"
```

---

### Task 5: 引入 BatchExecutionPolicy

**Files:**
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/policy/BatchExecutionPolicy.kt`
- Create: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/policy/BatchExecutionPolicyTest.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/BatchRegenerateSnapshotHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/event/state/ResendStateEventHandler.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxProperties.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfiguration.kt`

- [ ] **Step 1: 写 batch policy 测试**

Create `BatchExecutionPolicyTest.kt`:

```kotlin
package me.ahoo.wow.webflux.route.policy

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.kotlin.test.test

class BatchExecutionPolicyTest {
    @Test
    fun `should use serialized defaults`() {
        val policy = BatchExecutionPolicy()

        policy.concurrency.assert().isEqualTo(1)
        policy.prefetch.assert().isEqualTo(1)
    }

    @Test
    fun `should apply configured concurrency`() {
        val policy = BatchExecutionPolicy(concurrency = 4, prefetch = 8)

        policy.apply(Flux.just(1, 2, 3)) { value ->
            Flux.just(value * 2).single()
        }.test()
            .expectNext(2, 4, 6)
            .verifyComplete()
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
./gradlew :wow-webflux:test --tests "me.ahoo.wow.webflux.route.policy.BatchExecutionPolicyTest"
```

Expected: FAIL，提示 `Unresolved reference: BatchExecutionPolicy`。

- [ ] **Step 3: 新增 BatchExecutionPolicy**

Create `BatchExecutionPolicy.kt`:

```kotlin
package me.ahoo.wow.webflux.route.policy

import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

data class BatchExecutionPolicy(
    val concurrency: Int = 1,
    val prefetch: Int = 1
) {
    init {
        require(concurrency > 0) { "concurrency must be greater than 0." }
        require(prefetch > 0) { "prefetch must be greater than 0." }
    }

    fun <T : Any, R : Any> apply(source: Flux<T>, mapper: (T) -> Mono<R>): Flux<R> {
        return source.flatMapSequential(mapper, concurrency, prefetch)
    }
}
```

- [ ] **Step 4: 迁移 batch handler**

In `BatchRegenerateSnapshotHandlerFunction`, add constructor dependency:

```kotlin
private val batchExecutionPolicy: BatchExecutionPolicy = BatchExecutionPolicy()
```

Replace:

```kotlin
).flatMapSequential { aggregateId ->
```

with:

```kotlin
).let { aggregateIds ->
    batchExecutionPolicy.apply(aggregateIds) { aggregateId ->
```

and close the `let` before `.toBatchResult(afterId)`:

```kotlin
    }
}.toBatchResult(afterId).toServerResponse(request, exceptionHandler)
```

Use the same pattern in `ResendStateEventHandler`.

- [ ] **Step 5: 增加 properties**

Add to `WebFluxProperties`:

```kotlin
var batch: Batch = Batch()

data class Batch(
    @DefaultValue("1")
    var concurrency: Int = 1,
    @DefaultValue("1")
    var prefetch: Int = 1
)
```

- [ ] **Step 6: wire policy bean**

Add to `WebFluxAutoConfiguration`:

```kotlin
@Bean
@ConditionalOnMissingBean
fun batchExecutionPolicy(webFluxProperties: WebFluxProperties): BatchExecutionPolicy {
    return BatchExecutionPolicy(
        concurrency = webFluxProperties.batch.concurrency,
        prefetch = webFluxProperties.batch.prefetch
    )
}
```

Then pass `batchExecutionPolicy` into batch-related factories and handlers.

- [ ] **Step 7: 运行 batch 和 starter tests**

Run:

```bash
./gradlew :wow-webflux:test --tests "me.ahoo.wow.webflux.route.policy.BatchExecutionPolicyTest" \
  --tests "me.ahoo.wow.webflux.route.snapshot.BatchRegenerateSnapshotHandlerFunctionTest" \
  --tests "me.ahoo.wow.webflux.route.event.state.ResendStateEventHandlerTest"
./gradlew :wow-spring-boot-starter:test --tests "me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfigurationTest"
```

Expected: PASS。

- [ ] **Step 8: Commit**

```bash
git add wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/policy \
  wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/policy \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/BatchRegenerateSnapshotHandlerFunction.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/event/state/ResendStateEventHandler.kt \
  wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux
git commit -m "refactor(webflux): add batch execution policy"
```

---

### Task 6: 引入 TracingPolicy 与 windowed tracing

**Files:**
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/policy/TracingPolicy.kt`
- Create: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/policy/TracingPolicyTest.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingHandlerFunction.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingHandlerFunctionTest.kt`
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/aggregate/state/AggregateTracingRouteSpec.kt`
- Modify: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/aggregate/state/AggregateTracingRouteSpecTest.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/webflux/AggregateTracingBenchmark.kt`

- [ ] **Step 1: 写 TracingPolicy 测试**

Create `TracingPolicyTest.kt`:

```kotlin
package me.ahoo.wow.webflux.route.policy

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.reactive.function.server.MockServerRequest

class TracingPolicyTest {
    @Test
    fun `should default to full history`() {
        val range = TracingPolicy().range(MockServerRequest.builder().build(), totalVersion = 100)

        range.replayHead.assert().isEqualTo(1)
        range.emitHead.assert().isEqualTo(1)
        range.tail.assert().isEqualTo(100)
    }

    @Test
    fun `should emit tail window after full replay prefix`() {
        val request = MockServerRequest.builder()
            .queryParam("limit", "10")
            .build()

        val range = TracingPolicy().range(request, totalVersion = 100)

        range.replayHead.assert().isEqualTo(1)
        range.emitHead.assert().isEqualTo(91)
        range.tail.assert().isEqualTo(100)
    }

    @Test
    fun `should reject invalid range`() {
        val request = MockServerRequest.builder()
            .queryParam("headVersion", "10")
            .queryParam("tailVersion", "1")
            .build()

        assertThrows<IllegalArgumentException> {
            TracingPolicy().range(request, totalVersion = 100)
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
./gradlew :wow-webflux:test --tests "me.ahoo.wow.webflux.route.policy.TracingPolicyTest"
```

Expected: FAIL，提示 `Unresolved reference: TracingPolicy`。

- [ ] **Step 3: 新增 TracingPolicy**

Create `TracingPolicy.kt`:

```kotlin
package me.ahoo.wow.webflux.route.policy

import org.springframework.web.reactive.function.server.ServerRequest

data class TracingRange(
    val replayHead: Int,
    val emitHead: Int,
    val tail: Int
)

class TracingPolicy {
    fun range(request: ServerRequest, totalVersion: Int): TracingRange {
        val requestedHead = request.queryParam("headVersion").map { it.toInt() }.orElse(1)
        val requestedTail = request.queryParam("tailVersion").map { it.toInt() }.orElse(totalVersion)
        val limit = request.queryParam("limit").map { it.toInt() }.orElse(0)
        require(requestedHead > 0) { "headVersion must be greater than 0." }
        require(requestedTail >= requestedHead) { "tailVersion must be greater than or equal to headVersion." }
        require(limit >= 0) { "limit must be greater than or equal to 0." }
        val tail = requestedTail.coerceAtMost(totalVersion)
        val emitHead = if (limit > 0) {
            (tail - limit + 1).coerceAtLeast(requestedHead)
        } else {
            requestedHead
        }
        return TracingRange(
            replayHead = 1,
            emitHead = emitHead,
            tail = tail
        )
    }
}
```

- [ ] **Step 4: 增加 windowed trace helper**

In `AggregateTracingHandlerFunction.Companion`, add:

```kotlin
fun <S : Any> StateAggregateMetadata<S>.trace(
    stateAggregateFactory: StateAggregateFactory,
    eventStreams: List<DomainEventStream>,
    emitHeadVersion: Int,
    tailVersion: Int
): Flux<StateEvent<ObjectNode>> {
    if (eventStreams.isEmpty()) {
        return Flux.empty()
    }
    val stateAggregate = stateAggregateFactory.create(this, eventStreams.first().aggregateId)
    return Flux.fromIterable(eventStreams)
        .handle { eventStream, sink ->
            stateAggregate.onSourcing(eventStream)
            val version = eventStream.version
            if (version in emitHeadVersion..tailVersion) {
                sink.next(
                    eventStream.toStateEvent(
                        state = stateAggregate.state.toJsonNode<ObjectNode>(),
                        firstOperator = stateAggregate.firstOperator,
                        firstEventTime = stateAggregate.firstEventTime,
                        tags = stateAggregate.tags,
                        deleted = stateAggregate.deleted,
                    )
                )
            }
        }
}
```

Add imports:

```kotlin
import reactor.core.publisher.Flux
```

- [ ] **Step 5: 迁移 handler 使用 policy**

In `AggregateTracingHandlerFunction`, inject:

```kotlin
private val tracingPolicy: TracingPolicy = TracingPolicy()
```

Then change `handle` to:

```kotlin
val context = WowWebRequestContext.of(request, aggregateMetadata)
return eventStore
    .load(aggregateId = context.aggregateId)
    .collectList()
    .flatMapMany { eventStreams ->
        val range = tracingPolicy.range(request, totalVersion = eventStreams.size)
        aggregateMetadata.state.trace(
            stateAggregateFactory = stateAggregateFactory,
            eventStreams = eventStreams,
            emitHeadVersion = range.emitHead,
            tailVersion = range.tail
        )
    }.toServerResponse(request, exceptionHandler)
```

This keeps event loading simple in the first production step while changing output materialization to streaming response. Later storage-aware optimization can avoid reading unneeded tails.

- [ ] **Step 6: Add OpenAPI query parameters**

In `AggregateTracingRouteSpec`, add query parameter specs for:

```text
headVersion
tailVersion
limit
```

Keep all parameters optional and do not change path or method.

- [ ] **Step 7: 运行 tracing/openapi tests**

Run:

```bash
./gradlew :wow-webflux:test --tests "me.ahoo.wow.webflux.route.policy.TracingPolicyTest" \
  --tests "me.ahoo.wow.webflux.route.state.AggregateTracingHandlerFunctionTest"
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.aggregate.state.AggregateTracingRouteSpecTest"
```

Expected: PASS。

- [ ] **Step 8: 编译 JMH**

Run:

```bash
./gradlew :wow-benchmarks:compileJmhKotlin
```

Expected: BUILD SUCCESSFUL。

- [ ] **Step 9: Commit**

```bash
git add wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/policy/TracingPolicy.kt \
  wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/policy/TracingPolicyTest.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingHandlerFunction.kt \
  wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/state/AggregateTracingHandlerFunctionTest.kt \
  wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/aggregate/state/AggregateTracingRouteSpec.kt \
  wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/aggregate/state/AggregateTracingRouteSpecTest.kt \
  wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/webflux/AggregateTracingBenchmark.kt
git commit -m "feat(webflux): add aggregate tracing window policy"
```

---

### Task 7: 引入 CommandWaitPolicy 和 RemoteWaitNotifyPolicy

**Files:**
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/policy/CommandWaitPolicy.kt`
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/wait/RemoteWaitNotifyPolicy.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/command/CommandHandler.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/wait/WebClientCommandWaitNotifier.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WowWebClientAutoConfiguration.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxProperties.kt`
- Test: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/wait/WebClientCommandWaitNotifierTest.kt`
- Test: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/command/CommandHandlerTest.kt`

- [ ] **Step 1: 新增 CommandWaitPolicy**

Create `CommandWaitPolicy.kt`:

```kotlin
package me.ahoo.wow.webflux.route.policy

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.wait.WaitPlan
import me.ahoo.wow.webflux.route.command.extractWaitPlan
import me.ahoo.wow.webflux.route.command.getWaitTimeout
import org.springframework.web.reactive.function.server.ServerRequest
import java.time.Duration

class CommandWaitPolicy(
    private val defaultTimeout: Duration
) {
    fun waitPlan(request: ServerRequest, commandMessage: CommandMessage<Any>): WaitPlan {
        return request.extractWaitPlan(commandMessage)
    }

    fun timeout(request: ServerRequest): Duration {
        return request.getWaitTimeout(defaultTimeout)
    }
}
```

- [ ] **Step 2: 迁移 CommandHandler**

Change constructor:

```kotlin
class CommandHandler(
    private val commandGateway: CommandGateway,
    private val commandMessageExtractor: CommandMessageExtractor,
    private val commandWaitPolicy: CommandWaitPolicy = CommandWaitPolicy(DEFAULT_TIME_OUT)
)
```

Replace in `sendCommand`:

```kotlin
val waitPlan = commandWaitPolicy.waitPlan(request, commandMessage)
val commandWaitTimeout = commandWaitPolicy.timeout(request)
```

- [ ] **Step 3: 新增 RemoteWaitNotifyPolicy**

Create `RemoteWaitNotifyPolicy.kt`:

```kotlin
package me.ahoo.wow.webflux.wait

import me.ahoo.wow.messaging.handler.DEFAULT_RETRY_SPEC
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import reactor.util.retry.Retry

class RemoteWaitNotifyPolicy(
    val retry: Retry = DEFAULT_RETRY_SPEC,
    val scheduler: Scheduler = Schedulers.parallel()
) {
    fun <T> apply(publisher: Mono<T>): Mono<T> {
        return publisher.retryWhen(retry).subscribeOn(scheduler)
    }
}
```

- [ ] **Step 4: 迁移 WebClientCommandWaitNotifier**

Change constructor:

```kotlin
class WebClientCommandWaitNotifier(
    private val waitCoordinator: WaitCoordinator,
    private val webClient: WebClient,
    private val remoteWaitNotifyPolicy: RemoteWaitNotifyPolicy = RemoteWaitNotifyPolicy()
) : CommandWaitNotifier {
```

Replace remote branch tail:

```kotlin
.bodyToMono(Void::class.java)
.let { remoteWaitNotifyPolicy.apply(it) }
```

- [ ] **Step 5: wire starter**

In `WowWebClientAutoConfiguration`, provide:

```kotlin
@Bean
@ConditionalOnMissingBean
fun remoteWaitNotifyPolicy(): RemoteWaitNotifyPolicy {
    return RemoteWaitNotifyPolicy()
}
```

and pass it to `WebClientCommandWaitNotifier`.

- [ ] **Step 6: 运行 wait/command tests**

Run:

```bash
./gradlew :wow-webflux:test --tests "me.ahoo.wow.webflux.wait.WebClientCommandWaitNotifierTest" \
  --tests "me.ahoo.wow.webflux.route.command.CommandHandlerTest"
./gradlew :wow-spring-boot-starter:test --tests "me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfigurationTest"
```

Expected: PASS。

- [ ] **Step 7: Commit**

```bash
git add wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/policy/CommandWaitPolicy.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/wait/RemoteWaitNotifyPolicy.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/command/CommandHandler.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/wait/WebClientCommandWaitNotifier.kt \
  wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux
git commit -m "refactor(webflux): extract wait policies"
```

---

### Task 8: 拆分 route-family module 并清理过渡层

**Files:**
- Create: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/route/WebFluxRouteModule.kt`
- Create route module files under `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/route/`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfiguration.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionRegistrar.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouterFunctionBuilder.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/Responses.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/command/CommandResponses.kt`
- Test: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfigurationTest.kt`
- Test: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/RouterFunctionBuilderTest.kt`

- [ ] **Step 1: 新增 route module SPI**

Create `WebFluxRouteModule.kt`:

```kotlin
package me.ahoo.wow.spring.boot.starter.webflux.route

import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory

interface WebFluxRouteModule {
    val factories: List<RouteHandlerFunctionFactory<*>>
}
```

- [ ] **Step 2: 增加 CommandRouteModule**

Create `CommandRouteModule.kt` with constructor dependencies and factory list:

```kotlin
package me.ahoo.wow.spring.boot.starter.webflux.route

import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.wait.WaitCoordinator
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.command.CommandFacadeHandlerFunctionFactory
import me.ahoo.wow.webflux.route.command.CommandHandlerFunctionFactory
import me.ahoo.wow.webflux.route.command.extractor.CommandMessageExtractor
import me.ahoo.wow.webflux.wait.CommandWaitHandlerFunctionFactory

class CommandRouteModule(
    commandGateway: CommandGateway,
    commandMessageExtractor: CommandMessageExtractor,
    waitCoordinator: WaitCoordinator,
    exceptionHandler: RequestExceptionHandler
) : WebFluxRouteModule {
    override val factories: List<RouteHandlerFunctionFactory<*>> = listOf(
        CommandWaitHandlerFunctionFactory(waitCoordinator),
        CommandFacadeHandlerFunctionFactory(commandGateway, commandMessageExtractor, exceptionHandler),
        CommandHandlerFunctionFactory(commandGateway, commandMessageExtractor, exceptionHandler)
    )
}
```

- [ ] **Step 3: 增加 QueryRouteModule**

Create `QueryRouteModule.kt`:

```kotlin
package me.ahoo.wow.spring.boot.starter.webflux.route

import me.ahoo.wow.query.event.filter.EventStreamQueryHandler
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.CountEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.ListQueryEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.LoadEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.PagedQueryEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.query.RewriteRequestCondition
import me.ahoo.wow.webflux.route.snapshot.CountSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.ListQuerySnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.ListQuerySnapshotStateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.PagedQuerySnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.PagedQuerySnapshotStateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.SingleSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.SingleSnapshotStateHandlerFunctionFactory

class QueryRouteModule(
    snapshotQueryHandler: SnapshotQueryHandler,
    eventStreamQueryHandler: EventStreamQueryHandler,
    rewriteRequestCondition: RewriteRequestCondition,
    exceptionHandler: RequestExceptionHandler
) : WebFluxRouteModule {
    override val factories: List<RouteHandlerFunctionFactory<*>> = listOf(
        ListQuerySnapshotHandlerFunctionFactory(snapshotQueryHandler, rewriteRequestCondition, exceptionHandler),
        ListQuerySnapshotStateHandlerFunctionFactory(snapshotQueryHandler, rewriteRequestCondition, exceptionHandler),
        PagedQuerySnapshotHandlerFunctionFactory(snapshotQueryHandler, rewriteRequestCondition, exceptionHandler),
        PagedQuerySnapshotStateHandlerFunctionFactory(snapshotQueryHandler, rewriteRequestCondition, exceptionHandler),
        SingleSnapshotHandlerFunctionFactory(snapshotQueryHandler, rewriteRequestCondition, exceptionHandler),
        SingleSnapshotStateHandlerFunctionFactory(snapshotQueryHandler, rewriteRequestCondition, exceptionHandler),
        CountSnapshotHandlerFunctionFactory(snapshotQueryHandler, rewriteRequestCondition, exceptionHandler),
        LoadEventStreamHandlerFunctionFactory(eventStreamQueryHandler, exceptionHandler),
        ListQueryEventStreamHandlerFunctionFactory(eventStreamQueryHandler, rewriteRequestCondition, exceptionHandler),
        PagedQueryEventStreamHandlerFunctionFactory(eventStreamQueryHandler, rewriteRequestCondition, exceptionHandler),
        CountEventStreamHandlerFunctionFactory(eventStreamQueryHandler, rewriteRequestCondition, exceptionHandler)
    )
}
```

- [ ] **Step 4: 增加 SnapshotRouteModule**

Create `SnapshotRouteModule.kt`:

```kotlin
package me.ahoo.wow.spring.boot.starter.webflux.route

import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.BatchRegenerateSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.LoadSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.RegenerateSnapshotHandlerFunctionFactory

class SnapshotRouteModule(
    stateAggregateFactory: StateAggregateFactory,
    eventStore: EventStore,
    snapshotRepository: SnapshotRepository,
    exceptionHandler: RequestExceptionHandler
) : WebFluxRouteModule {
    override val factories: List<RouteHandlerFunctionFactory<*>> = listOf(
        LoadSnapshotHandlerFunctionFactory(snapshotRepository, exceptionHandler),
        RegenerateSnapshotHandlerFunctionFactory(stateAggregateFactory, eventStore, snapshotRepository, exceptionHandler),
        BatchRegenerateSnapshotHandlerFunctionFactory(stateAggregateFactory, eventStore, snapshotRepository, exceptionHandler)
    )
}
```

- [ ] **Step 5: 增加 StateRouteModule**

Create `StateRouteModule.kt`:

```kotlin
package me.ahoo.wow.spring.boot.starter.webflux.route

import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.state.AggregateTracingHandlerFunctionFactory
import me.ahoo.wow.webflux.route.state.LoadAggregateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.state.LoadTimeBasedAggregateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.state.LoadVersionedAggregateHandlerFunctionFactory

class StateRouteModule(
    stateAggregateRepository: StateAggregateRepository,
    stateAggregateFactory: StateAggregateFactory,
    eventStore: EventStore,
    exceptionHandler: RequestExceptionHandler
) : WebFluxRouteModule {
    override val factories: List<RouteHandlerFunctionFactory<*>> = listOf(
        LoadAggregateHandlerFunctionFactory(stateAggregateRepository, exceptionHandler),
        LoadVersionedAggregateHandlerFunctionFactory(stateAggregateRepository, exceptionHandler),
        LoadTimeBasedAggregateHandlerFunctionFactory(stateAggregateRepository, exceptionHandler),
        AggregateTracingHandlerFunctionFactory(stateAggregateFactory, eventStore, exceptionHandler)
    )
}
```

- [ ] **Step 6: 增加 EventRouteModule**

Create `EventRouteModule.kt`:

```kotlin
package me.ahoo.wow.spring.boot.starter.webflux.route

import me.ahoo.wow.event.compensation.StateEventCompensator
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.messaging.compensation.EventCompensateSupporter
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.EventCompensateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.state.ResendStateEventFunctionFactory

class EventRouteModule(
    snapshotRepository: SnapshotRepository,
    stateEventCompensator: StateEventCompensator,
    eventCompensateSupporter: EventCompensateSupporter,
    exceptionHandler: RequestExceptionHandler
) : WebFluxRouteModule {
    override val factories: List<RouteHandlerFunctionFactory<*>> = listOf(
        ResendStateEventFunctionFactory(snapshotRepository, stateEventCompensator, exceptionHandler),
        EventCompensateHandlerFunctionFactory(eventCompensateSupporter, exceptionHandler)
    )
}
```

- [ ] **Step 7: 增加 GlobalRouteModule**

Create `GlobalRouteModule.kt`:

```kotlin
package me.ahoo.wow.spring.boot.starter.webflux.route

import me.ahoo.wow.spring.boot.starter.kafka.KafkaProperties
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.global.GenerateBIScriptHandlerFunctionFactory
import me.ahoo.wow.webflux.route.global.GetWowMetadataHandlerFunctionFactory
import me.ahoo.wow.webflux.route.global.GlobalIdHandlerFunctionFactory

class GlobalRouteModule(
    kafkaProperties: KafkaProperties?
) : WebFluxRouteModule {
    override val factories: List<RouteHandlerFunctionFactory<*>> = listOf(
        GlobalIdHandlerFunctionFactory(),
        GenerateBIScriptHandlerFunctionFactory(
            kafkaBootstrapServers = kafkaProperties?.bootstrapServersToString().orEmpty(),
            topicPrefix = kafkaProperties?.topicPrefix.orEmpty()
        ),
        GetWowMetadataHandlerFunctionFactory()
    )
}
```

- [ ] **Step 8: 让 registrar 接受 module factories**

Change `routeHandlerFunctionRegistrar` bean to collect modules and standalone factories:

```kotlin
@Bean
fun routeHandlerFunctionRegistrar(
    modules: ObjectProvider<WebFluxRouteModule>,
    factories: ObjectProvider<RouteHandlerFunctionFactory<*>>
): RouteHandlerFunctionRegistrar {
    val registrar = RouteHandlerFunctionRegistrar()
    modules.orderedStream().forEach { module ->
        module.factories.forEach { registrar.register(it) }
    }
    factories.orderedStream().forEach { registrar.register(it) }
    return registrar
}
```

- [ ] **Step 9: 改 registrar 为不可变索引**

Replace mutable registration internals with a builder-style API:

```kotlin
class RouteHandlerFunctionRegistrar(
    factories: Collection<RouteHandlerFunctionFactory<*>> = emptyList()
) {
    private val factoryIndex: Map<Class<out RouteSpec>, RouteHandlerFunctionFactory<*>> =
        factories.associateBy { it.supportedSpec }

    fun getFactory(spec: RouteSpec): RouteHandlerFunctionFactory<*>? {
        return factoryIndex[spec::class.java]
    }
}
```

Then update the Spring bean to construct `RouteHandlerFunctionRegistrar(allFactories)` directly.

- [ ] **Step 10: 改 RouterFunctionBuilder 诊断**

Change missing factory message:

```kotlin
"RouteHandlerFunctionFactory not found - method:[${routeSpec.method}], path:[${routeSpec.path}], spec:[${routeSpec::class.java.name}]."
```

- [ ] **Step 11: 清理 response facades**

Inspect `Responses.kt` and `CommandResponses.kt`. Keep only functions that delegate to `DefaultWebFluxResponseStrategy`; remove local branching, local SSE construction, and default `Flux.collectList()` behavior.

The remaining `Flux<T>.toServerResponse` body must be exactly:

```kotlin
return DefaultWebFluxResponseStrategy.jsonArray(this, request, exceptionHandler)
```

- [ ] **Step 12: 运行完整窄验证**

Run:

```bash
./gradlew :wow-webflux:test
./gradlew :wow-spring-boot-starter:test --tests "me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfigurationTest"
./gradlew :wow-benchmarks:compileJmhKotlin
```

Expected: all commands BUILD SUCCESSFUL。

- [ ] **Step 13: 运行 quick WebFlux benchmark**

Run:

```bash
./gradlew :wow-benchmarks:benchmarkQuickWebFlux -PbenchmarkQuickThreads=1
./gradlew :wow-benchmarks:generateQuickBenchmarkReport -PbenchmarkQuickThreads=1
```

Expected: BUILD SUCCESSFUL. Do not commit generated `wow-benchmarks/results` unless the user explicitly asks for report updates.

- [ ] **Step 14: Commit**

```bash
git add wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route \
  wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux \
  wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/webflux \
  wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route
git commit -m "refactor(webflux): modularize route registration"
```

---

## Final Verification

- [ ] Run:

```bash
./gradlew :wow-webflux:test
./gradlew :wow-spring-boot-starter:test --tests "me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfigurationTest"
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.aggregate.state.AggregateTracingRouteSpecTest"
./gradlew :wow-benchmarks:compileJmhKotlin
```

Expected: all commands BUILD SUCCESSFUL。

- [ ] Run quick benchmark:

```bash
./gradlew :wow-benchmarks:benchmarkQuickWebFlux -PbenchmarkQuickThreads=1
./gradlew :wow-benchmarks:generateQuickBenchmarkReport -PbenchmarkQuickThreads=1
```

Expected: BUILD SUCCESSFUL。

- [ ] Check forbidden architecture leftovers:

```bash
rg -n "collectList\\(" wow-webflux/src/main/kotlin
rg -n "Schedulers\\.boundedElastic\\(" wow-webflux/src/main/kotlin
rg -n "ServerSentEvent\\.builder" wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route
```

Expected:

- `collectList()` appears only in explicitly bounded or temporary paths with comments.
- `Schedulers.boundedElastic()` does not appear in `WebClientCommandWaitNotifier`.
- `ServerSentEvent.builder` appears only in response strategy code or in thin delegated helpers.

- [ ] Check working tree:

```bash
git status --short
```

Expected: no unintended changes. If benchmark results changed and the user did not ask to commit reports, restore `wow-benchmarks/results`.
