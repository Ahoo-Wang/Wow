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

package me.ahoo.wow.spring.boot.http

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spring.boot.http.NodePublicPort.Companion.exposure
import org.junit.jupiter.api.Test

/**
 * The startup line of a node in a development profile that takes its callers from request headers names where its
 * public port listens when other machines reach it (#6662): it changes no address, it says which.
 */
class DevelopmentExposureTest {
    @Test
    fun `a public port on every interface or on a network address is named`() {
        exposure(null).assert().contains("every interface").contains("server.address=127.0.0.1")
        exposure(" ").assert().contains("every interface")
        exposure("0.0.0.0").assert().contains("listens on 0.0.0.0")
        exposure("192.168.71.40").assert().contains("listens on 192.168.71.40")
        // A name that resolves to nothing (RFC 6761 reserves .invalid) is no loopback address either.
        exposure("wow.invalid").assert().contains("listens on wow.invalid")
    }

    @Test
    fun `a public port on the loopback interface adds nothing`() {
        exposure("127.0.0.1").assert().isEmpty()
        exposure("::1").assert().isEmpty()
        exposure("localhost").assert().isEmpty()
    }
}
