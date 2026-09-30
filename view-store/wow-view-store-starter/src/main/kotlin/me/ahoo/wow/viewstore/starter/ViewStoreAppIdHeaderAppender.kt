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

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.webflux.route.command.appender.CommandRequestHeaderAppender
import org.springframework.web.reactive.function.server.ServerRequest

/**
 * Carries a view store command's `CoSec-App-Id` into its header, where the aggregates read it, and only that: an
 * `app_id` another appender took from the caller (Wow's `Command-Header-app_id`) is removed first, so a command
 * without `CoSec-App-Id` carries no application. It is the header key CoSec's own appender writes, so a host with
 * CoSec gets the same value twice; other commands of the host are left alone.
 *
 * Appenders run in no fixed order, so [ViewStoreWebFilter] also drops every `Command-Header-*` from the request.
 */
class ViewStoreAppIdHeaderAppender(private val paths: ViewStorePaths) : CommandRequestHeaderAppender {
    override fun append(request: ServerRequest, header: Header) {
        if (!paths.isViewStorePath(request.requestPath().pathWithinApplication())) {
            return
        }
        header.remove(ViewStoreService.APP_ID_MESSAGE_HEADER)
        request.headers().firstHeader(ViewStoreService.APP_ID_HEADER)?.takeIf { it.isNotBlank() }?.let {
            header.with(ViewStoreService.APP_ID_MESSAGE_HEADER, it)
        }
    }
}
