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
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.policy.ReadAction
import me.ahoo.wow.query.policy.ReadAuthorization
import me.ahoo.wow.query.schema.QueryModel
import org.junit.jupiter.api.Test

/** A read of the view store needs an application; which application's it reads is the access policies'. */
class ViewStoreAppsTest {
    private val read = ReadAction.of(
        AggregateType("view-store", "view"),
        QueryModel.SNAPSHOT,
        QueryType.LIST,
        QueryEntry.HTTP
    )

    @Test
    fun `a caller with an application reads, and one without is refused as malformed`(): Unit = runBlocking {
        val apps = ViewStoreApps()
        apps.authorize(Caller.of(subject = "alice", attributes = mapOf(Caller.APP to listOf("console"))), read)
            .assert().isEqualTo(ReadAuthorization.Allowed)
        val refused = apps.authorize(Caller.of(subject = "alice"), read) as ReadAuthorization.Denied
        refused.code.assert().isEqualTo(FrameworkCodes.QUERY_MALFORMED)
        refused.message.assert().contains("no application")
    }
}
