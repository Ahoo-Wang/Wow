# Prepare Key

Compared to traditional databases, developers can add uniqueness constraints by setting `UNIQUE KEY` for fields.
But in the _EventSourcing_ architecture, we need to ensure `Key` uniqueness at the application level, and the `PrepareKey` specification was born for this purpose.

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