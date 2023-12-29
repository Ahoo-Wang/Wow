# 预分配 Key

相比传统数据库，开发者可以通过为字段设置 `UNIQUE KEY` 来增加唯一性约束。
但在 _EventSourcing_ 架构中，我们需要在应用层面保证 `Key` 的唯一性，`PrepareKey` 规范就是为此而生。

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