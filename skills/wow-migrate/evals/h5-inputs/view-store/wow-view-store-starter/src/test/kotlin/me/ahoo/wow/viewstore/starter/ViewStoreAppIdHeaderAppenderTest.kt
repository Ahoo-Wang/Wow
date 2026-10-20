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

package me.ahoo.wow.viewstore.starter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.viewstore.ViewStoreService
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import java.net.URI

class ViewStoreAppIdHeaderAppenderTest {
    private val appender = ViewStoreAppIdHeaderAppender(ViewStorePaths(MaterializedNamedBoundedContext("host")))

    private fun append(path: String, appId: String?, clientAppId: String? = null): String? {
        val builder = MockServerRequest.builder().uri(URI.create("http://localhost$path"))
        appId?.let { builder.header(ViewStoreService.APP_ID_HEADER, it) }
        val header = DefaultHeader.empty()
        // What Wow's extend appender took from a caller's `Command-Header-app_id`.
        clientAppId?.let { header.with(ViewStoreService.APP_ID_MESSAGE_HEADER, it) }
        appender.append(builder.build(), header)
        return header[ViewStoreService.APP_ID_MESSAGE_HEADER]
    }

    @Test
    fun `carries the application of a view store command`() {
        append("/view-store/tenant/t1/owner/alice/view", "console").assert().isEqualTo("console")
    }

    @Test
    fun `leaves other commands and requests without an application alone`() {
        append("/execution_failed/1/prepare", "console").assert().isNull()
        append("/view-store/tenant/t1/owner/alice/view", null).assert().isNull()
        append("/view-store/tenant/t1/owner/alice/view", " ").assert().isNull()
    }

    @Test
    fun `the application comes from CoSec-App-Id only`() {
        append("/view-store/tenant/t1/owner/alice/view", null, clientAppId = "portal").assert().isNull()
        append(
            "/view-store/tenant/t1/owner/alice/view",
            "console",
            clientAppId = "portal"
        ).assert().isEqualTo("console")
    }
}
