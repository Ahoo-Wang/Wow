# FastInvoke MethodInvoker 性能优化设计

## 背景

`FastInvoke` 当前是 `Method.invoke` 和 `Constructor.newInstance` 的薄包装，主要价值是统一异常解包和避免 Kotlin 调用点使用 spread operator。它没有改变反射调用本身的成本，也没有消除高频单参数调用中的数组分配。

当前热点调用链中，`SimpleMessageFunctionAccessor` 对无注入消息函数每次都会执行 `arrayOf(firstArgument)`，再经过 `FunctionAccessor.invoke`、`FastInvoke.safeInvoke` 和 `Method.invoke`。这条路径覆盖命令处理、事件 sourcing、projection、saga 等常见业务函数。对于 command write path，这属于高频小对象分配和反射分派开销。

本设计采用元数据绑定阶段缓存专用 invoker 的方案。目标是在不引入编译期生成、不新增外部依赖的前提下，拿到大部分可见收益，并为后续更激进的生成式 invoker 留出扩展点。

## 设计目标

1. 在 accessor 创建阶段完成 invoker 绑定，避免每次调用时重复判断和临时适配。
2. 为单参数消息函数提供 `invokeSingle` fast path，消除每次 `arrayOf(firstArgument)` 分配。
3. 使用缓存的 `MethodHandle` 作为首选执行路径，失败时安全降级到现有 reflection 行为。
4. 保留多参数和注入参数函数的现有语义，不改变服务注入、消息参数解析或异常传播规则。
5. 为构造器提供 0/1/2 参数 fast path，覆盖状态聚合构造和聚合模式 command root 构造。
6. 用 JMH 同时验证 accessor 微基准和 command pipeline 框架级收益，性能结论只来自当前 checkout 的基准输出。

## 非目标

本次不引入 KSP 生成 invoker，不改变业务函数声明方式，不新增第三方依赖，不改变 command/event 协议，不改变 projection、saga、command handler 的公共语义。`LambdaMetafactory` 不作为第一版实现目标，只保留工厂扩展点，等待 JMH 证明 `MethodHandle` 路径不足时再评估。

Suspend 函数和 Flow 返回函数继续使用 Kotlin reflection 的 `callSuspend` 路径。它们的调用模型不同，第一版不把协程适配和普通 Java method invocation 混在一起优化。

## 总体架构

新增一个低层调用抽象，例如 `MethodInvoker`，用于封装已绑定的 Java `Method` 调用策略：

```kotlin
interface MethodInvoker {
    fun invoke(target: Any, args: Array<Any?>): Any?
    fun invokeSingle(target: Any, arg: Any?): Any?
}
```

`MethodInvokerFactory` 负责根据 `Method` 创建 invoker。默认优先创建 `MethodHandleMethodInvoker`，如果访问权限、方法形态或平台差异导致创建失败，则降级到 `ReflectionMethodInvoker`。降级路径必须和现有 `FastInvoke.safeInvoke` 的行为一致。

`FunctionAccessor` 持有并复用该 invoker。`SimpleFunctionAccessor` 和 `AbstractMonoFunctionAccessor` 在初始化时确保方法 accessible，然后创建一次 invoker。运行期只做目标对象、参数和返回值传递。

`FastInvoke` 作为兼容入口保留。现有 `invoke`、`safeInvoke`、`newInstance`、`safeNewInstance` 可以继续存在，避免一次性扩大 API 破坏面。新的高频调用点应优先走 `FunctionAccessor.invokeSingle` 或构造器 fast path。

## Invoker 策略

第一版采用两级策略：

| 策略 | 用途 | 说明 |
| --- | --- | --- |
| `MethodHandleMethodInvoker` | 默认快速路径 | 初始化时 `unreflect(method)` 并适配 erased 签名，运行期走稳定调用点 |
| `ReflectionMethodInvoker` | 保底路径 | 创建 `MethodHandle` 失败时回到 `Method.invoke`，保持兼容性 |

单参数 fast path 的目标签名固定为：

```kotlin
(target: Any, arg: Any?) -> Any?
```

多参数路径继续接收 `Array<Any?>`。这样可以先优化最常见、收益最明确的无注入消息函数，同时避免为任意参数数量建立复杂的调用矩阵。

异常语义保持现状。业务方法抛出的异常必须向上传递原始异常，不让 `InvocationTargetException` 泄漏到命令、事件、projection 或 saga 链路。`MethodHandle` 直接抛出的 `Throwable` 也按原始异常传播。

## FunctionAccessor 变更

`FunctionAccessor` 增加单参数 API：

```kotlin
fun invokeSingle(target: T, arg: Any?): R
```

默认实现可以委托到 `invoke(target, arrayOf(arg))`，保证自定义实现无需立即修改。框架内置实现覆盖该方法，使用缓存 invoker。

`invoke(target, args)` 继续保留，用于注入参数、多参数函数、测试辅助和兼容调用点。

`SimpleFunctionAccessor` 的职责从“确保 accessible 后每次使用 `Method.invoke`”变为“确保 accessible 并持有缓存 invoker”。它仍然暴露 `function`、`method`、`targetType`、`name` 等元数据。

## MessageFunctionAccessor 路径

`SimpleMessageFunctionAccessor` 是第一优先级优化点。它没有注入参数，当前只需要把从 exchange 提取出的第一个参数传给业务函数。优化后调用路径为：

```kotlin
val firstArgument = metadata.extractFirstArgument(exchange)
return metadata.accessor.invokeSingle(processor, firstArgument)
```

这样命令函数、事件函数、状态 sourcing 函数、projection 函数和 saga 函数的无注入路径都能避免参数数组分配。

`InjectableMessageFunctionAccessor` 保持数组路径。它必须继续解析 `@Name` 服务和按类型注入的服务，参数数量也不固定。后续如果 JMH 显示注入路径也成为热点，再单独设计 `invoke2`、`invoke3` 或注入参数缓存策略。

## Reactive Accessor 路径

`AbstractMonoFunctionAccessor` 创建并缓存 invoker，普通 reactive 子类复用该 invoker：

- `SimpleMonoFunctionAccessor`
- `SyncMonoFunctionAccessor`
- `FluxMonoFunctionAccessor`
- `PublisherMonoFunctionAccessor`

这些子类仍保持原有 reactive lazy 语义：`Mono.defer` 或 `Mono.fromCallable` 的边界不变，只替换边界内部的底层 method invocation。

`SuspendMonoFunctionAccessor` 和 `FlowMonoFunctionAccessor` 暂时保留 `function.callSuspend(target, *args)`。它们涉及 Kotlin suspend continuation 和 Flow 收集，第一版不混入 `MethodHandle` 优化。

## ConstructorAccessor 路径

构造器路径增加 0/1/2 参数 fast path：

```kotlin
fun newInstance0(): T
fun newInstance1(arg: Any?): T
fun newInstance2(arg1: Any?, arg2: Any?): T
```

`ConstructorAccessor.invoke(args)` 继续保留，用于可变参数和注入式对象工厂。`DefaultConstructorAccessor` 初始化时为构造器创建缓存 invoker 或 constructor invoker。

`ConstructorStateAggregateFactory` 当前只支持状态构造器 0/1/2 参数，因此可以直接改用 fast path：

- 0 参数：`newInstance0()`
- 1 参数：`newInstance1(aggregateId.id)`
- 2 参数：`newInstance2(aggregateId.id, aggregateId.tenantId)`

`SimpleCommandAggregateFactory` 在聚合模式下用 state aggregate 构造 command root，可以改用 `newInstance1(stateAggregate.state)`。

## API 兼容策略

本次可接受 API 变更，但仍保持温和演进：

1. 不删除 `FastInvoke` 现有方法。
2. 不删除 `FunctionAccessor.invoke(target, args)`。
3. 不删除 `ConstructorAccessor.invoke(args)`。
4. 新增 fast path API，并把框架内部热点调用切换到新 API。
5. 文档和测试说明新 API 是高频调用推荐入口，旧 API 仍是通用入口。

这样可以让已有外部扩展继续工作，同时让框架默认路径获得性能收益。

## 错误处理

所有 invoker 都必须保持现有异常语义：

- 被调用业务方法抛出的异常按原始异常向上传递。
- reflection fallback 必须解包 `InvocationTargetException.targetException`。
- constructor fallback 必须解包构造器内部异常。
- `IllegalAccessException`、`IllegalArgumentException` 等调用错误继续按调用错误传播。

`MethodInvokerFactory` 创建失败不应让框架启动失败，除非 fallback 也无法工作。创建失败时降级到 reflection invoker，并保留测试覆盖。

## 测试设计

新增或更新 `wow-core` 单元测试，覆盖以下行为：

1. `MethodInvoker` 可调用 public 方法、private 方法、单参数方法、多参数方法和无参数方法。
2. `invokeSingle` 和 `invoke(args)` 返回值一致。
3. 业务异常保持原始异常传播，不泄漏 `InvocationTargetException`。
4. varargs 方法保持现有调用方式，传入 `Object[]{String[]}` 时结果不变。
5. `SimpleMessageFunctionAccessor` 无注入函数走单参数路径，结果和现有行为一致。
6. `InjectableMessageFunctionAccessor` 仍正确解析命名服务和类型服务。
7. `ConstructorAccessor` 0/1/2 参数 fast path 支持私有构造器。
8. 构造器内部异常按原始异常传播。
9. reactive accessor 保持 lazy 语义和返回类型适配。

验证命令：

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.infra.accessor.*"
./gradlew :wow-core:test --tests "me.ahoo.wow.messaging.function.*"
./gradlew :wow-core:test --tests "me.ahoo.wow.modeling.*"
./gradlew :wow-core:check
```

## JMH 验证设计

在 `wow-benchmarks` 增加 accessor microbenchmark，专门比较底层调用方式：

| Benchmark | 目的 |
| --- | --- |
| `methodInvokeArray` | 现有 `Method.invoke` 数组路径基线 |
| `methodHandleArray` | 缓存 `MethodHandle` 多参数路径 |
| `methodHandleSingle` | 缓存 `MethodHandle` 单参数路径 |
| `functionAccessorInvoke` | 框架 `FunctionAccessor.invoke` 路径 |
| `functionAccessorInvokeSingle` | 框架 `FunctionAccessor.invokeSingle` 路径 |
| `constructorInvokeArray` | 现有构造器数组路径 |
| `constructorInvoke0/1/2` | 构造器 fast path |

框架级验证继续使用现有 benchmark：

```bash
./gradlew :wow-benchmarks:benchmarkSmoke
./gradlew :wow-benchmarks:benchmarkInternal
```

重点观察两类指标：

- throughput 是否提升。
- `gc.alloc.rate.norm` 是否下降。

性能结论只以当前 checkout 的 JMH 输出为准，不引用历史未验证数据。

## 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| `MethodHandle` 对私有方法或特殊 Kotlin 方法创建失败 | 创建失败时降级 reflection invoker |
| 单参数 fast path 改变异常包装 | 单元测试覆盖业务异常原样传播 |
| varargs 语义被破坏 | 保留数组路径测试，单参数 fast path 不特殊展开 varargs |
| reactive lazy 边界被改变 | 只替换 defer/callable 内部 invocation，测试 StepVerifier 行为 |
| 构造器 fast path 破坏私有构造器 | 初始化继续 `ensureAccessible`，测试私有构造器 |
| 微基准提升但框架级收益不明显 | 同时运行 accessor microbenchmark 和 command pipeline benchmark |

## 验收标准

1. `SimpleMessageFunctionAccessor` 无注入路径不再为第一个参数创建数组。
2. `FunctionAccessor.invokeSingle` 使用缓存 invoker，而不是每次临时反射适配。
3. `MethodHandle` 创建失败时自动 fallback 到 reflection，业务行为不变。
4. 构造器 0/1/2 参数 fast path 覆盖状态聚合和聚合模式 command root 构造。
5. `:wow-core:check` 通过。
6. `:wow-benchmarks:benchmarkSmoke` 通过。
7. accessor microbenchmark 能展示 array path 和 single path 的吞吐、分配差异。
8. command pipeline benchmark 能用于判断框架级收益。

## 后续扩展

如果第一版 JMH 显示 `MethodHandle` 仍不是最优，可以在同一 `MethodInvokerFactory` 下增加 `LambdaMetafactory` invoker。只有在以下条件满足时再推进：

1. accessor microbenchmark 显示 `LambdaMetafactory` 明显优于 `MethodHandle`。
2. 私有方法、Kotlin bridge、返回 `Unit`、基本类型装箱和异常语义都有测试覆盖。
3. command pipeline benchmark 证明框架级收益足以抵消复杂度。

KSP 生成直接调用 invoker 是理论收益上限最高的方案，但它会扩大到编译期模型、生成代码和模块边界。本次不进入该范围。
