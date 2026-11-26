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

package me.ahoo.wow.spring.boot.node

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.runtime.access.EventsSource
import me.ahoo.wow.runtime.store.StoreLayout
import me.ahoo.wow.spring.boot.query.EventQueryBackend
import org.junit.jupiter.api.Test

/**
 * The host tells the node where each type's `events` model is served from, as its query plane bound it: the type's log
 * store in place, another store (an Elasticsearch store whose events Sink indexes it), or nowhere (#7157).
 */
class EventsSourcesTest {
    private val cart = AggregateType("shop", "cart")
    private val order = AggregateType("shop", "order")
    private val item = AggregateType("shop", "item")
    private val layout = StoreLayout("main")

    @Test
    fun `each type's events model is served from its log, another store or nowhere`() {
        val events = EventQueryBackend(store = { type ->
            when (type) {
                cart -> "main"
                order -> "search"
                else -> null
            }
        }) { null }
        val sources = eventsSources(layout, events)
        sources.of(cart).assert().isEqualTo(EventsSource.Log)
        sources.of(order).assert().isEqualTo(EventsSource.Store("search"))
        sources.of(item).assert().isNull()
    }

    @Test
    fun `without the host's stores or its events backend, no type is checked`() {
        val events = EventQueryBackend(store = { "main" }) { null }
        eventsSources(null, events).of(cart).assert().isNull()
        eventsSources(layout, null).of(cart).assert().isNull()
    }
}
