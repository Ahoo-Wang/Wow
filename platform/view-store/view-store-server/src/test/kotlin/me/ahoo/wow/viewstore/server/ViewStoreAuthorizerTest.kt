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

package me.ahoo.wow.viewstore.server

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.viewstore.api.ViewStoreContext
import me.ahoo.wow.viewstore.api.preferences.SetViewPreferences
import me.ahoo.wow.viewstore.api.view.RenameView
import org.junit.jupiter.api.Test

/** The view store's write rules (decision D92), with those of reviews R2.3-2 and R2.3-3. */
class ViewStoreAuthorizerTest {
    private val authorizer = ViewStoreAuthorizer()

    private fun rename(scope: Scope) = CommandAction(
        CommandType("${ViewStoreContext.NAME}.${ViewStoreContext.VIEW}.RenameView", RenameView::class.java),
        AggregateType(ViewStoreContext.NAME, ViewStoreContext.VIEW).ref("t1", "v-1"),
        scope,
        Creation.NONE,
    )

    private fun preferences(scope: Scope) = CommandAction(
        CommandType(
            "${ViewStoreContext.NAME}.${ViewStoreContext.VIEW_PREFERENCES}.SetViewPreferences",
            SetViewPreferences::class.java,
        ),
        AggregateType(ViewStoreContext.NAME, ViewStoreContext.VIEW_PREFERENCES).ref("t1", "p-1"),
        scope,
        Creation.NONE,
    )

    private fun caller(owner: String?, vararg roles: String) = Caller.of(
        tenantId = "t1",
        scope = Scope(ownerId = owner),
        subject = owner,
        crossScope = true,
        attributes = mapOf(ViewStoreCallers.ROLES_ATTRIBUTE to roles.toList()),
    )

    private fun allowed(caller: Caller, action: CommandAction): Boolean =
        runBlocking { authorizer.authorize(caller, action) } is Authorization.Allowed

    @Test
    fun `a write that acts as no owner should be refused whatever the caller's roles`() {
        val tenantLevel = caller(null, ViewStoreCallers.SHARED_WRITER, ViewStoreCallers.SYSTEM_WRITER)
        allowed(tenantLevel, rename(Scope.NONE)).assert().isFalse()
        allowed(caller("alice"), rename(Scope.NONE)).assert().isFalse()
        allowed(caller("alice"), rename(Scope(ownerId = "alice"))).assert().isTrue()
        allowed(caller("alice"), rename(Scope(ownerId = ViewStoreContext.SHARED_OWNER_ID))).assert().isFalse()
        allowed(
            caller("carol", ViewStoreCallers.SHARED_WRITER),
            rename(Scope(ownerId = ViewStoreContext.SHARED_OWNER_ID)),
        ).assert().isTrue()
    }

    @Test
    fun `preferences should be written in the caller's own scope alone`() {
        val admin = caller("admin", ViewStoreCallers.SHARED_WRITER)
        allowed(admin, preferences(Scope(ownerId = "admin"))).assert().isTrue()
        // The transformer derived the id from the admin's own owner: written as (shared), it would be the wrong one.
        allowed(admin, preferences(Scope(ownerId = ViewStoreContext.SHARED_OWNER_ID))).assert().isFalse()
        // The dev callers act as (shared) with that owner as their own.
        val shared = caller(ViewStoreContext.SHARED_OWNER_ID, ViewStoreCallers.SHARED_WRITER)
        allowed(shared, preferences(Scope(ownerId = ViewStoreContext.SHARED_OWNER_ID))).assert().isTrue()
    }

    private fun refusal(caller: Caller, action: CommandAction): String =
        (runBlocking { authorizer.authorize(caller, action) } as Authorization.Denied).message

    @Test
    fun `preferences written as no owner, or by a caller without one, should be refused naming the missing owner`() {
        refusal(caller("alice", ViewStoreCallers.SHARED_WRITER), preferences(Scope.NONE)).assert()
            .contains("as no owner")
            .contains("own owner (alice)")
        val tenantLevel = caller(null, ViewStoreCallers.SHARED_WRITER, ViewStoreCallers.SYSTEM_WRITER)
        refusal(tenantLevel, preferences(Scope(ownerId = "alice"))).assert()
            .contains("An anonymous caller may not")
            .contains("as alice")
            .contains("own owner (none)")
    }

    @Test
    fun `a write that acts as no owner should be refused before the roles are consulted`() {
        val tenantLevel = caller(null, ViewStoreCallers.SHARED_WRITER, ViewStoreCallers.SYSTEM_WRITER)
        refusal(tenantLevel, rename(Scope.NONE)).assert().contains("and this one names none")
        refusal(caller("alice"), rename(Scope(ownerId = ViewStoreContext.SHARED_OWNER_ID))).assert()
            .contains("it needs ${ViewStoreCallers.SHARED_WRITER}")
    }
}
