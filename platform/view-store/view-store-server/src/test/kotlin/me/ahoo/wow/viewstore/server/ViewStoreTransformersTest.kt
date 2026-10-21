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
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.runtime.admission.CommandInput
import me.ahoo.wow.runtime.admission.CommandTransformer
import me.ahoo.wow.viewstore.api.preferences.SetViewPreferences
import me.ahoo.wow.viewstore.api.wording.ClearDatasetWording
import me.ahoo.wow.viewstore.api.wording.SetDatasetWording
import me.ahoo.wow.viewstore.domain.ScopedIds
import org.junit.jupiter.api.Test

/** The derived ids of the preferences and the wording: from the owner, the application and what they are for. */
class ViewStoreTransformersTest {
    private val alice = Caller.of(scope = Scope(ownerId = "alice"))

    private fun CommandTransformer.target(body: Any, app: String?, named: String? = null): String? {
        val headers = app?.let { mapOf(RequestFacts.APP_ID to it) }.orEmpty()
        val input = CommandInput(this.input, body, named, headers, "request-1", alice)
        return runBlocking { transform(input) }.aggregateId
    }

    @Test
    fun `the preferences and the wording should be sent to their derived ids, whatever the request names`() {
        PreferencesTarget().target(SetViewPreferences("orders"), "console", named = "someone-else").assert()
            .isEqualTo(ScopedIds.preferences(alice.tenantId, "alice", "console", "orders"))
        WordingTarget().target(SetDatasetWording("sales"), "console").assert()
            .isEqualTo(ScopedIds.wording(alice.tenantId, "console", "sales"))
        WordingClearTarget().target(ClearDatasetWording("sales"), " console ").assert()
            .isEqualTo(ScopedIds.wording(alice.tenantId, "console", "sales"))
    }

    @Test
    fun `without an application nothing should be derived`() {
        PreferencesTarget().target(SetViewPreferences("orders"), null).assert().isNull()
        WordingTarget().target(SetDatasetWording("sales"), " ").assert().isNull()
        WordingClearTarget().target(ClearDatasetWording("sales"), null).assert().isNull()
    }
}
