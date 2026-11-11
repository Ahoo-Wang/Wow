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

package me.ahoo.wow.benchmark.search

import me.ahoo.test.asserts.assert
import org.apache.hc.core5.http.message.BasicHttpRequest
import org.junit.jupiter.api.Test

class SearchRequestsTest {
    @Test
    fun `a request is counted by the last segment of its path, without its query`() {
        SearchRequests.kindOf("/_bulk").assert().isEqualTo("_bulk")
        SearchRequests.kindOf("/wow_events__transfer__account/_bulk?refresh=false").assert().isEqualTo("_bulk")
        SearchRequests.kindOf("/wow_events__transfer__account/_refresh").assert().isEqualTo("_refresh")
        SearchRequests.kindOf("/wow_events__transfer__account/_update_by_query?conflicts=proceed")
            .assert().isEqualTo("_update_by_query")
    }

    @Test
    fun `any other request is other`() {
        SearchRequests.kindOf("/wow_events__transfer__account/_search").assert().isEqualTo("other")
        SearchRequests.kindOf("/").assert().isEqualTo("other")
        SearchRequests.kindOf(null).assert().isEqualTo("other")
    }

    @Test
    fun `the snapshot names every kind, those never sent at 0`() {
        val requests = SearchRequests()
        requests.process(BasicHttpRequest("POST", "/_bulk"), null, null)
        requests.process(BasicHttpRequest("POST", "/_bulk"), null, null)
        requests.process(BasicHttpRequest("GET", "/i/_search"), null, null)
        requests.snapshot().assert().isEqualTo("""{"_bulk":2,"_refresh":0,"_update_by_query":0,"other":1}""")
    }
}
