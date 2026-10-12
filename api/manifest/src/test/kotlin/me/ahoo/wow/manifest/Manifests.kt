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

package me.ahoo.wow.manifest

/** Sample fragments for the tests. */
object Manifests {
    fun cart(context: String = "shop", aggregate: String = "cart"): AggregateManifest = AggregateManifest(
        name = aggregate,
        description = "A shopping cart",
        state = StateManifest(Manifest.schemaRef("sample.CartState"), "abc123"),
        commands = listOf(
            CommandManifest(
                name = "CreateCart",
                schema = Manifest.schemaRef("sample.CreateCart"),
                creation = Creation.REQUIRED,
                produces = listOf("CartCreated"),
            ),
            CommandManifest(
                name = "PutItem",
                aliases = listOf("AddItem"),
                schema = Manifest.schemaRef("sample.AddItem"),
                produces = null,
            ),
        ),
        events = listOf(
            EventManifest(name = "CartCreated", schema = Manifest.schemaRef("sample.CartCreated")),
            EventManifest(
                name = "ItemPut",
                aliases = listOf("ItemAdded", "$context.basket.ItemAdded"),
                revision = 2,
                schema = Manifest.schemaRef("sample.ItemAdded"),
            ),
        ),
    )

    fun fragment(
        context: String = "shop",
        aggregates: List<AggregateManifest> = listOf(cart(context)),
        defs: Map<String, Any?> = mapOf("sample.CartState" to mapOf("type" to "object")),
    ): Manifest = Manifest(
        contexts = listOf(
            ContextManifest(
                name = context,
                description = "The shop",
                aggregates = aggregates,
                subscriptions = listOf(
                    SubscriptionManifest(
                        name = "CartSummary.onEvent",
                        kind = SubscriptionKind.PROJECTION,
                        source = SubscriptionSource(context = context, aggregates = listOf("cart")),
                        function = FunctionManifest(FunctionKind.PROJECT, context, "CartSummary", "onEvent"),
                    ),
                ),
            ),
        ),
        defs = defs,
    )
}
