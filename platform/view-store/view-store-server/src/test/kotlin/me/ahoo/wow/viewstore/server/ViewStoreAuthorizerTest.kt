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
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.viewstore.api.ViewStoreContext
import me.ahoo.wow.viewstore.api.ViewStoreContract
import me.ahoo.wow.viewstore.api.preferences.SetViewPreferences
import me.ahoo.wow.viewstore.api.view.RenameView
import me.ahoo.wow.viewstore.api.view.ShareView
import org.junit.jupiter.api.Test

/** The view store's write rules (decision D92), with those of reviews R2.3-2 and R2.3-3. */
class ViewStoreAuthorizerTest {
    private val authorizer = ViewStoreAuthorizer()

    private fun rename(scope: Scope) = CommandAction.of(
        CommandType.of(ViewStoreContract.View.Commands.RENAME_VIEW, RenameView::class.java),
        ViewStoreContract.View.TYPE.ref("t1", "v-1"),
        scope,
        Creation.NONE,
    )

    private fun share(scope: Scope) = CommandAction.of(
        CommandType.of(ViewStoreContract.View.Commands.SHARE_VIEW, ShareView::class.java),
        ViewStoreContract.View.TYPE.ref("t1", "v-1"),
        scope,
        Creation.NONE,
    )

    private fun preferences(scope: Scope) = CommandAction.of(
        CommandType.of(
            ViewStoreContract.ViewPreferences.Commands.SET_VIEW_PREFERENCES,
            SetViewPreferences::class.java,
        ),
        ViewStoreContract.ViewPreferences.TYPE.ref("t1", "p-1"),
        scope,
        Creation.NONE,
    )

    private fun caller(owner: String?, vararg roles: String) = Caller.of(
        tenantId = "t1",
        scope = Scope(ownerId = owner),
        subject = owner,
        crossScope = true,
        attributes = mapOf(Caller.ROLES to roles.toList()),
    )

    private fun allowed(caller: Caller, action: CommandAction): Boolean =
        runBlocking { authorizer.authorize(caller, action) } is Authorization.Allowed

    @Test
    fun `a write that acts as no owner should be refused whatever the caller's roles`() {
        val tenantLevel = caller(null, ViewStoreRoles.SHARED_WRITER, ViewStoreRoles.SYSTEM_WRITER)
        allowed(tenantLevel, rename(Scope.NONE)).assert().isFalse()
        allowed(caller("alice"), rename(Scope.NONE)).assert().isFalse()
        allowed(caller("alice"), rename(Scope(ownerId = "alice"))).assert().isTrue()
        allowed(caller("alice"), rename(Scope(ownerId = ViewStoreContext.SHARED_OWNER_ID))).assert().isFalse()
        allowed(
            caller("carol", ViewStoreRoles.SHARED_WRITER),
            rename(Scope(ownerId = ViewStoreContext.SHARED_OWNER_ID)),
        ).assert().isTrue()
    }

    @Test
    fun `preferences should be written in the caller's own scope alone`() {
        val admin = caller("admin", ViewStoreRoles.SHARED_WRITER)
        allowed(admin, preferences(Scope(ownerId = "admin"))).assert().isTrue()
        // The transformer derived the id from the admin's own owner: written as (shared), it would be the wrong one.
        allowed(admin, preferences(Scope(ownerId = ViewStoreContext.SHARED_OWNER_ID))).assert().isFalse()
        // The dev callers act as (shared) with that owner as their own.
        val shared = caller(ViewStoreContext.SHARED_OWNER_ID, ViewStoreRoles.SHARED_WRITER)
        allowed(shared, preferences(Scope(ownerId = ViewStoreContext.SHARED_OWNER_ID))).assert().isTrue()
    }

    private fun refusal(caller: Caller, action: CommandAction): String =
        (runBlocking { authorizer.authorize(caller, action) } as Authorization.Denied).message

    @Test
    fun `preferences written as no owner, or by a caller without one, should be refused naming the missing owner`() {
        refusal(caller("alice", ViewStoreRoles.SHARED_WRITER), preferences(Scope.NONE)).assert()
            .contains("as no owner")
            .contains("own owner (alice)")
        val tenantLevel = caller(null, ViewStoreRoles.SHARED_WRITER, ViewStoreRoles.SYSTEM_WRITER)
        refusal(tenantLevel, preferences(Scope(ownerId = "alice"))).assert()
            .contains("An anonymous caller may not")
            .contains("as alice")
            .contains("own owner (none)")
    }

    @Test
    fun `a write that acts as no owner should be refused before the roles are consulted`() {
        val tenantLevel = caller(null, ViewStoreRoles.SHARED_WRITER, ViewStoreRoles.SYSTEM_WRITER)
        refusal(tenantLevel, rename(Scope.NONE)).assert().contains("and this one names none")
        refusal(caller("alice"), rename(Scope(ownerId = ViewStoreContext.SHARED_OWNER_ID))).assert()
            .contains("it needs ${ViewStoreRoles.SHARED_WRITER}")
    }

    @Test
    fun `sharing a personal view should need the role that writes the shared owner, though it is sent as its user`() {
        // The saga moves the view to (shared) with a derived command, which no authorizer checks: the share is where
        // publishing to the tenant's shared list is decided (#6920).
        val mine = Scope(ownerId = "alice")
        allowed(caller("alice"), share(mine)).assert().isFalse()
        refusal(caller("alice"), share(mine)).assert().contains("it needs ${ViewStoreRoles.SHARED_WRITER}")
        allowed(caller("alice", ViewStoreRoles.SHARED_WRITER), share(mine)).assert().isTrue()
        // A user's other writes of its own views need no role.
        allowed(caller("alice"), rename(mine)).assert().isTrue()
        // A reserved owner's share takes that owner's role, as its other writes: a stored system view's share reaches
        // the view, which refuses it as read-only.
        val system = Scope(ownerId = ViewStoreContext.SYSTEM_OWNER_ID)
        allowed(caller("admin", ViewStoreRoles.SYSTEM_WRITER), share(system)).assert().isTrue()
    }
}
