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

package me.ahoo.wow.openapi

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

internal class HttpsTest {

    @Test
    fun `should have correct header constant`() {
        Https.Header.ACCEPT.assert().isEqualTo("Accept")
    }

    @Test
    fun `should have correct status code constants`() {
        Https.Code.OK.assert().isEqualTo("200")
        Https.Code.BAD_REQUEST.assert().isEqualTo("400")
        Https.Code.NOT_FOUND.assert().isEqualTo("404")
        Https.Code.REQUEST_TIMEOUT.assert().isEqualTo("408")
        Https.Code.CONFLICT.assert().isEqualTo("409")
        Https.Code.GONE.assert().isEqualTo("410")
        Https.Code.TOO_MANY_REQUESTS.assert().isEqualTo("429")
    }

    @Test
    fun `should have correct method constants`() {
        Https.Method.GET.assert().isEqualTo("GET")
        Https.Method.POST.assert().isEqualTo("POST")
        Https.Method.PUT.assert().isEqualTo("PUT")
        Https.Method.DELETE.assert().isEqualTo("DELETE")
        Https.Method.PATCH.assert().isEqualTo("PATCH")
        Https.Method.HEAD.assert().isEqualTo("HEAD")
        Https.Method.OPTIONS.assert().isEqualTo("OPTIONS")
        Https.Method.TRACE.assert().isEqualTo("TRACE")
    }

    @Test
    fun `should have correct media type constants`() {
        Https.MediaType.APPLICATION_JSON.assert().isEqualTo("application/json")
        Https.MediaType.APPLICATION_SQL.assert().isEqualTo("application/sql")
        Https.MediaType.TEXT_PLAIN.assert().isEqualTo("text/plain")
        Https.MediaType.TEXT_EVENT_STREAM.assert().isEqualTo("text/event-stream")
    }
}
