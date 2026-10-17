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

package me.ahoo.wow.migrate.example

import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.FunctionKind
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import me.ahoo.wow.migrate.MigrationMap
import me.ahoo.wow.migrate.MigrationPlan
import me.ahoo.wow.migrate.NineXExecutionFailed
import me.ahoo.wow.spi.json.WowJson

/**
 * The 9.x compensation service's executions of the 9.x example, the fixtures of the compensation import (roadmap
 * 8b.4), and their V10 port.
 *
 * `ninex-compensation/exec-failed-<n>-snapshot.json` are what the 9.x compensation service stored: the 9.x
 * `ExecutionFailed` aggregate (`wow-compensation-domain`) sourced with the 9.x events its commands produce, its
 * snapshot serialized by the 9.x `SnapshotSerializer`, as 9.x's `MongoSnapshotStore` writes it (`aggregateId` is the
 * document's `_id`). Each failed on an event of the 9.x example's order `0VY3GwaK4OmV00E`, tenant `(0)`:
 *
 * 1. `CartSaga.onOrderCreated` at v1: failed, no retry yet, the default spec (10, 180 s, 120 s);
 * 2. `OrderSaga.onEvent` at v1: retried once and failed again, marked `RECOVERABLE`, spec (5, 60 s, 30 s) applied;
 * 3. `CartSaga.onOrderCreated` at v2: succeeded on its retry (resolved);
 * 4. `OrderSaga.onStateEvent` (a state event) at v3: `UNRECOVERABLE`, a retry in flight (`PREPARED`);
 * 5. `CartSaga.onOrderCreated` at v4: its 9.x aggregate deleted (skipped, resolved);
 * 6. `OrderProjector.onOrderPaid` at v3: failed;
 * 7. `CartSaga.onOrderCreated` at v1 of the order `0VYMissingOrder`, which no 9.x stream holds.
 *
 * The V10 port keeps `CartSaga.onOrderCreated`, names the order saga's handlers `OrderSaga.onOrderCreated` and
 * `OrderSaga.onOrderState`, and moves the projector to a `reporting` context that reads the published order events.
 */
object NineXCompensationExample {
    const val ORDER_ID = "0VY3GwaK4OmV00E"
    const val TENANT = "(0)"
    const val CART_SAGA = "CartSaga.onOrderCreated"
    const val ORDER_CREATED = "OrderSaga.onOrderCreated"
    const val ORDER_STATE = "OrderSaga.onOrderState"
    const val ORDER_PAID = "OrderProjector.onOrderPaid"

    val FIXTURES = (1..7).map { "exec-failed-$it-snapshot.json" }

    /** The text of the 9.x snapshot of execution [n]. */
    fun resource(n: Int): String =
        requireNotNull(javaClass.classLoader.getResource("ninex-compensation/${FIXTURES[n - 1]}")).readText()

    /** The 9.x execution [n]. */
    fun execution(n: Int): NineXExecutionFailed = NineXExecutionFailed.parse(WowJson.mapper.readTree(resource(n)))

    /** Every execution of the example. */
    fun executions(): List<NineXExecutionFailed> = (1..FIXTURES.size).map(::execution)

    /** The subscription mappings the Skill writes for the port, as JSON. */
    const val SUBSCRIPTIONS: String = """
      { "from": "example-service.CartSaga" },
      { "from": "example-service.OrderSaga",
        "functions": { "onEvent": "onOrderCreated", "onStateEvent": "onOrderState" } },
      { "from": "example-service.OrderProjector", "functions": { "onOrderPaid": "OrderProjector.onOrderPaid" } }"""

    /** The example's map with [subscriptions] (JSON array items). */
    fun mapText(subscriptions: String = SUBSCRIPTIONS): String =
        NineXExample.MAP_TEXT.trimEnd().removeSuffix("}") + """, "subscriptions": [ $subscriptions ] }"""

    val map: MigrationMap get() = MigrationMap.read(mapText())

    val manifest: Manifest = Manifest(
        contexts = NineXExample.manifest.contexts.map { context ->
            ContextManifest(
                name = context.name,
                aggregates = context.aggregates,
                subscriptions = listOf(
                    saga(CART_SAGA, "order"),
                    saga(ORDER_CREATED, "order"),
                    saga(ORDER_STATE, "order"),
                ),
            )
        } + ContextManifest(
            name = "reporting",
            subscriptions = listOf(
                SubscriptionManifest(
                    name = ORDER_PAID,
                    kind = SubscriptionKind.PROJECTION,
                    source = SubscriptionSource(context = NineXExample.CONTEXT, aggregates = listOf("order")),
                    function = FunctionManifest(FunctionKind.PROJECT, "reporting", "OrderProjector", "onOrderPaid"),
                ),
            ),
        ),
    )

    val MANIFEST_TEXT: String get() = NineXExample.manifestText(manifest)

    val plan: MigrationPlan get() = MigrationPlan(map, manifest)

    private fun saga(name: String, aggregate: String): SubscriptionManifest {
        val (processor, function) = name.split('.')
        return SubscriptionManifest(
            name = name,
            kind = SubscriptionKind.SAGA,
            source = SubscriptionSource(aggregates = listOf(aggregate)),
            function = FunctionManifest(FunctionKind.SAGA, NineXExample.CONTEXT, processor, function),
        )
    }
}
