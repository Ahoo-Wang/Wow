# 预分配 Key

相比传统数据库，开发者可以通过为字段设置 `UNIQUE KEY` 来增加唯一性约束。
但在 _EventSourcing_ 架构中，我们需要在应用层面保证 `Key` 的唯一性，`PrepareKey` 规范就是为此而生。

## 定义 PrepareKey

要使用 `PrepareKey`，需要定义一个接口，并使用 `@PreparableKey` 注解标注，该接口需要继承 `PrepareKey<T>`，其中 `T` 为 Key 的类型。

```kotlin
import me.ahoo.wow.api.annotation.PreparableKey
import me.ahoo.wow.infra.prepare.PrepareKey

@PreparableKey(name = "username_idx")
interface UsernamePrepare : PrepareKey<String>
```

- `@PreparableKey(name = "username_idx")`：指定预分配 Key 的名称，用于标识该 Key 在存储中的索引。
- `PrepareKey<String>`：泛型参数 `String` 表示 Key 的类型为字符串。

框架会自动扫描标注了 `@PreparableKey` 注解的接口，并通过 `PrepareKeyProxyFactory` 创建代理实例，将其注册为 Spring Bean，Bean 名称为接口的简单名称。这样就可以在聚合中使用依赖注入的方式获取 `PrepareKey` 实例。

## PrepareKey 接口方法

`PrepareKey<V>` 接口提供了以下核心方法：

### 准备 Key

- `prepare(key: String, value: V)`：永久准备一个 Key，确保其他操作无法使用相同的 Key 直到显式回滚。
- `prepare(key: String, value: PreparedValue<V>)`：准备一个 Key，支持 TTL（生存时间），过期后自动失效。

### 获取准备的值

- `get(key: String)`：获取已准备的非过期值。
- `getValue(key: String)`：获取完整的准备值信息，包括过期状态。

### 回滚 Key

- `rollback(key: String)`：无条件回滚指定的 Key。
- `rollback(key: String, value: V)`：条件回滚，只有当值匹配时才回滚，确保原子性。

### 重新准备 Key

- `reprepare(key: String, oldValue: V, newValue: V)`：更新现有 Key 的值，使用旧值进行并发控制。
- `reprepare(oldKey: String, oldValue: V, newKey: String, newValue: V)`：原子性地释放旧 Key 并准备新 Key，常用于用户名变更等场景。

### 使用准备上下文执行操作

- `usingPrepare(key: String, value: V, then: (Boolean) -> Mono<R>)`：在准备上下文中执行操作，如果操作失败自动回滚准备，提供事务-like 语义。

## PreparedValue 与 TTL 支持

`PreparedValue<V>` 封装了值和可选的 TTL 配置：

- 永久准备：`value.toForever()`
- 带 TTL 准备：`PreparedValue(value, Duration.ofMinutes(5))`

TTL 支持允许临时预留 Key，过期后自动清理，适用于需要清理的临时操作。

## 用户注册场景

例如当用户注册时，需要保证用户名的唯一性：

```kotlin
@AggregateRoot
class User(private val state: UserState) {
    @OnCommand
    private fun onRegister(
        register: Register,
        passwordEncoder: PasswordEncoder,
        usernamePrepare: UsernamePrepare,
    ): Mono<Registered> {
        val encodedPassword = passwordEncoder.encode(register.password)
        return usernamePrepare.usingPrepare(
            key = register.username,
            value = UsernameIndexValue(
                userId = state.id,
                password = encodedPassword,
            ),
        ) {
            require(it) {
                "username[${register.username}] is already registered."
            }
            Registered(username = register.username, password = encodedPassword).toMono()
        }
    }
}
```

## 用户修改用户名场景

例如当用户修改用户名时，需要保证新用户名的唯一性，以及回滚掉旧的用户名：

```kotlin
    @OnCommand
    private fun onChangeUsername(
        changeUsername: ChangeUsername,
        usernamePrepare: UsernamePrepare
    ): Mono<UsernameChanged> {
        val usernameIndexValue = UsernameIndexValue(
            userId = state.id,
            password = state.password,
        )
        return usernamePrepare.reprepare(
            oldKey = state.username,
            oldValue = usernameIndexValue,
            newKey = changeUsername.newUsername,
            newValue = usernameIndexValue
        ).map {
            require(it) {
                "username[${changeUsername.newUsername}] is already registered."
            }
            UsernameChanged(username = changeUsername.newUsername)
        }
    }
```