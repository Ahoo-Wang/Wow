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

package me.ahoo.wow.spring.boot.starter.command

import me.ahoo.cosid.machine.HostAddressSupplier
import me.ahoo.wow.command.wait.CommandWaitEndpoint
import me.ahoo.wow.openapi.global.CommandWaitRouteSpecFactory
import org.springframework.boot.web.server.context.WebServerInitializedEvent
import org.springframework.context.ApplicationListener
import kotlin.properties.Delegates

class ServerCommandWaitEndpoint(hostAddressSupplier: HostAddressSupplier) :
    CommandWaitEndpoint,
    ApplicationListener<WebServerInitializedEvent> {
    override val endpoint: String by lazy {
        "http://$ipAddress:$port${CommandWaitRouteSpecFactory.PATH}"
    }
    private val ipAddress = hostAddressSupplier.hostAddress
    private var port by Delegates.notNull<Int>()
    override fun onApplicationEvent(event: WebServerInitializedEvent) {
        port = event.webServer.port
    }
}
