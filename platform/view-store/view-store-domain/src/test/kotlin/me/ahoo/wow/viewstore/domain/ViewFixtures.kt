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
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.viewstore.api.ViewStoreContext

/** What the view store's specifications send: owners, callers and configs. */
object ViewFixtures {
    const val APP = "orders-console"
    const val OTHER_APP = "other-console"
    const val ALICE = "alice"
    const val BOB = "bob"
    const val TENANT = "tenant"

    fun owner(ownerId: String): Scope = Scope(ownerId = ownerId)

    /** The signed-in user [id] of [TENANT], in their own scope, calling from [app] (from none when it is null). */
    fun user(id: String, app: String? = APP): Caller =
        Caller.of(tenantId = TENANT, scope = owner(id), subject = id, attributes = appOf(app))

    /** No user of [TENANT], calling in [scope] from [app] (from none when it is null). */
    fun nobody(scope: Scope = Scope.NONE, app: String? = APP): Caller =
        Caller.of(tenantId = TENANT, scope = scope, attributes = appOf(app))

    /**
     * [caller] as the view store's server admits a request of theirs whose `Wow-Scope` names the reserved [scope]
     * (`ReservedScopeCallers`): acting as that owner, across scopes. A specification names [scope] beside it, as the
     * request names it.
     */
    fun reserved(caller: Caller, scope: Scope): Caller = Caller.of(
        tenantId = caller.tenantId,
        scope = scope,
        subject = caller.subject,
        crossScope = true,
        attributes = caller.attributes,
        service = caller.service,
    )

    /** The user [id] of [TENANT], in their own scope from [app], allowed to act across scopes (an operator's). */
    fun crossing(id: String, app: String? = APP): Caller =
        Caller.of(tenantId = TENANT, scope = owner(id), subject = id, crossScope = true, attributes = appOf(app))

    /** The saga's derived command, sent by no caller: an anonymous one of [TENANT], from no application. */
    val SAGA: Caller = Caller.of(tenantId = TENANT)

    private fun appOf(app: String?): Map<String, List<String>> = app?.let { mapOf(Caller.APP to listOf(it)) }.orEmpty()

    val SHARED: Scope = owner(ViewStoreContext.SHARED_OWNER_ID)
    val SYSTEM: Scope = owner(ViewStoreContext.SYSTEM_OWNER_ID)

    fun recordConfig(): Map<String, Any?> = mapOf("kind" to "record", "columns" to listOf("id", "status"))

    fun dashboardConfig(vararg views: String): Map<String, Any?> = mapOf(
        "kind" to "dashboard",
        "panels" to views.map { mapOf("instanceId" to it, "click" to mapOf("instanceId" to it)) },
    )
}
