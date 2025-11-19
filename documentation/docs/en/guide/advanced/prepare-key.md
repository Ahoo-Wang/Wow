# Prepare Key

Compared to traditional databases, developers can add uniqueness constraints by setting `UNIQUE KEY` for fields.
But in the _EventSourcing_ architecture, we need to ensure `Key` uniqueness at the application level, and the `PrepareKey` specification was born for this purpose.

## Define PrepareKey

To use `PrepareKey`, you need to define an interface annotated with `@PreparableKey`, which must extend `PrepareKey<T>`, where `T` is the type of the key.

```kotlin
import me.ahoo.wow.api.annotation.PreparableKey
import me.ahoo.wow.infra.prepare.PrepareKey

@PreparableKey(name = "username_idx")
interface UsernamePrepare : PrepareKey<String>
```

- `@PreparableKey(name = "username_idx")`: Specifies the name of the prepared key, used to identify the key's index in storage.
- `PrepareKey<String>`: The generic parameter `String` indicates the key type is string.

The framework automatically scans interfaces annotated with `@PreparableKey`, creates proxy instances through `PrepareKeyProxyFactory`, and registers them as Spring Beans with the interface's simple name as the bean name. This allows dependency injection to obtain `PrepareKey` instances in aggregates.

## PrepareKey Interface Methods

The `PrepareKey<V>` interface provides the following core methods:

### Prepare Key

- `prepare(key: String, value: V)`: Permanently prepares a key, ensuring other operations cannot use the same key until explicitly rolled back.
- `prepare(key: String, value: PreparedValue<V>)`: Prepares a key with TTL (Time-To-Live) support, automatically expiring after the specified time.

### Get Prepared Value

- `get(key: String)`: Retrieves a non-expired prepared value.
- `getValue(key: String)`: Retrieves complete prepared value information, including expiration status.

### Rollback Key

- `rollback(key: String)`: Unconditionally rolls back the specified key.
- `rollback(key: String, value: V)`: Conditionally rolls back only if the value matches, ensuring atomicity.

### Reprepare Key

- `reprepare(key: String, oldValue: V, newValue: V)`: Updates the value of an existing key, using the old value for concurrency control.
- `reprepare(oldKey: String, oldValue: V, newKey: String, newValue: V)`: Atomically releases the old key and prepares the new key, commonly used for scenarios like username changes.

### Execute Operation in Prepare Context

- `usingPrepare(key: String, value: V, then: (Boolean) -> Mono<R>)`: Executes an operation within a prepare context, automatically rolling back preparation if the operation fails, providing transaction-like semantics.

## PreparedValue and TTL Support

`PreparedValue<V>` encapsulates the value and optional TTL configuration:

- Permanent preparation: `value.toForever()`
- TTL preparation: `PreparedValue(value, Duration.ofMinutes(5))`

TTL support allows temporary key reservations that automatically clean up after expiration, suitable for temporary operations requiring cleanup.

## User Registration Scenario

For example, when a user registers, the uniqueness of the username needs to be guaranteed:

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

## User Change Username Scenario

For example, when a user changes their username, the uniqueness of the new username needs to be guaranteed, and the old username needs to be rolled back:

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