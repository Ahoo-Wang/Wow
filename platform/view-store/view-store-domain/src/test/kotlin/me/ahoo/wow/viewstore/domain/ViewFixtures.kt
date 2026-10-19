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

package me.ahoo.wow.viewstore.domain

import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.viewstore.api.ViewStoreContext

/** What the view store's specifications send: owners, applications, operators and configs. */
object ViewFixtures {
    const val APP = "orders-console"
    const val OTHER_APP = "other-console"
    const val ALICE = "alice"
    const val BOB = "bob"
    const val TENANT = "tenant"

    val APP_HEADERS: Map<String, String> = app(APP)

    fun app(appId: String): Map<String, String> = mapOf(RequestFacts.APP_ID to appId)

    fun owner(ownerId: String): Scope = Scope(ownerId = ownerId)

    val SHARED: Scope = owner(ViewStoreContext.SHARED_OWNER_ID)
    val SYSTEM: Scope = owner(ViewStoreContext.SYSTEM_OWNER_ID)

    fun operator(id: String): Operator = Operator.of(id, TENANT)

    fun recordConfig(): Map<String, Any?> = mapOf("kind" to "record", "columns" to listOf("id", "status"))

    fun dashboardConfig(vararg views: String): Map<String, Any?> = mapOf(
        "kind" to "dashboard",
        "panels" to views.map { mapOf("instanceId" to it, "click" to mapOf("instanceId" to it)) },
    )
}
