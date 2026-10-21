---
name: b139-migrate-ts-client-code
tags: [behavior, typescript, migration]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

Port this call from our app's 9.x Wow client to V10. Do not modify files.

```ts
const result = await commandClient.send<AddCartItem>({
  url: '/owner/{ownerId}/cart/{id}/add_cart_item',
  urlParams: { path: { ownerId: user.id, id: cartId } },
  headers: commandHeaders({ waitStrategy: waitStrategy({ stage: CommandStage.SNAPSHOT }) }),
  body: { productId, quantity, price: 19.9 },
});
if (result.errorCode !== ErrorCodes.SUCCEEDED) toast(result.errorMsg);
``` Give the code and the commands in your answer.
