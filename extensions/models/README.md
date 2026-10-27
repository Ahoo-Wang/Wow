# wow-models

Shared model helpers for Wow domains, ported from 9.x `wow-models`:

- `me.ahoo.wow.models.common`: capability interfaces (`NameCapable`, `NotBlankNameCapable`, `TitleCapable`,
  `RemarkCapable`, `ReasonCapable`, `QuantityCapable`, `TypeCapable`, `ItemIdCapable`, `CompletedCapable`,
  `TerminatedCapable`, `TenantAwareIdCapable`), `EnumText` and `StatusCapable`.
- `me.ahoo.wow.models.tree`: tree codes (`TreeCoded`, `Info`, `Flat`, `Leaf`), `Trees.toTree` and `ITreeState`.
- `me.ahoo.wow.models.tree.aggregate`: the reusable `Tree` aggregate and its `TreeState`, with the commands and
  events of `me.ahoo.wow.models.tree.command` (`Create`, `Update`, `Delete`, `Move`).

```kotlin
dependencies {
    implementation("me.ahoo.wow:wow-models")
}
```

## A tree aggregate

Subclass `Tree` and `TreeState` with your commands and events; the compiler binds the handlers they inherit:

```kotlin
@AggregateRoot(ownership = Ownership.TENANT)
class Category(state: CategoryState) :
    Tree<CategoryState, CreateCategory, UpdateCategory, DeleteCategory, MoveCategory>(state) {
    override fun maxLevel(): Int = 3
}

class CategoryState(override val id: String) :
    TreeState<FlatCategory, CategoryCreated, CategoryUpdated, CategoryDeleted, CategoryMoved>() {
    override fun Flat.toFlat(): FlatCategory = FlatCategory(name, code, sortId)
}

@AllowCreate
data class CreateCategory(override val name: String, override val parentCode: String) : Create<CategoryCreated> {
    override fun toEvent(code: String, sortId: Int) = CategoryCreated(name, code, sortId)
}
```

- Annotate the concrete create command `@AllowCreate`: the compiler reads the command's own class.
- Creating a node answers its code under the result key `code`. The code's last segment comes from
  `generateCode(scope)`, by default a global id of the decision without `-`.
- A broken rule is a rejection with a `TreeRejections` code (`tree.parent-not-found`, `tree.max-level-exceeded`,
  `tree.node-not-found`, `tree.node-has-children`, `tree.invalid-move`). The compiler lists only your module's
  `@ErrorCode` constants in its Manifest; declare the ones you want with a status there, with the same literal.
- Override `verifyCreate`, `verifyUpdate` or `verifyDelete` to add rules of your domain (they suspend, so they may do
  I/O), and the `...ErrorMessage` functions to change the messages.
