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

package me.ahoo.wow.cosec.appender

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.cosec.propagation.CoSecMessagePropagator.Companion.withAppId
import me.ahoo.wow.cosec.propagation.CoSecMessagePropagator.Companion.withDeviceId
import me.ahoo.wow.webflux.route.command.appender.CommandRequestHeaderAppender
import org.springframework.web.reactive.function.server.ServerRequest

object CoSecCommandRequestHeaderAppender : CommandRequestHeaderAppender {
    const val APP_ID_KEY = "CoSec-App-Id"
    const val DEVICE_ID_KEY = "CoSec-Device-Id"
    override fun append(
        request: ServerRequest,
        header: Header
    ) {
        request.headers().firstHeader(APP_ID_KEY)?.let {
            header.withAppId(it)
        }
        request.headers().firstHeader(DEVICE_ID_KEY)?.let {
            header.withDeviceId(it)
        }
    }
}
