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

package me.ahoo.wow.spi.store

import me.ahoo.wow.api.annotation.WowSpi

/**
 * A backend of the node's stores (review R3.3-1): what opens a store instance of one [type], `wow.stores.<name>.type`
 * (decision D177), or the one store `wow.backend` names. The built-in PostgreSQL (`postgres`) and MongoDB (`mongo`)
 * backends are providers as a community backend is; the host finds them on the classpath (`ServiceLoader`, from the
 * backend module's `META-INF/services/me.ahoo.wow.spi.store.StoreProvider`) and among the application's beans, a bean
 * taking the place of a provider of its type on the classpath.
 *
 * A provider is stateless: [open] gives each store instance its own [Store], over the resources [StoreConfig.resources]
 * finds (a `DataSource`, a driver's client) and the settings [StoreConfig.options] holds.
 */
@WowSpi
interface StoreProvider {
    /** The type a store's configuration names: lowercase, unique among the providers of a node. */
    val type: String

    /**
     * What a store of this type needs from the application, in the words of a startup failure that says it is missing
     * (`a DataSource bean`, say).
     */
    val requirement: String get() = "its resources"

    /**
     * Whether [resources] are enough to open a durable store of this type with no setting of its own: `wow.backend=auto`
     * takes the one provider that is available (PostgreSQL with a `DataSource`, MongoDB with a `MongoClient`).
     */
    fun available(resources: StoreResources): Boolean = false

    /** Opens the store [config] configures. It opens nothing in the database yet: its ports open on first use. */
    fun open(config: StoreConfig): Store
}
