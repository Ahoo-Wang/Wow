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

import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse

/** The view store's own routes; Wow generates the aggregates' command and query routes beside them. */
object ViewStoreRoutes {
    fun routerFunction(paths: ViewStorePaths, handlers: ViewStoreHandlers): RouterFunction<ServerResponse> =
        RouterFunctions.route()
            .GET(paths.systemViews, handlers::systemViews)
            .GET(paths.systemView, handlers::systemView)
            .GET(paths.preferences, handlers::getPreferences)
            .PUT(paths.preferences, handlers::setPreferences)
            .GET(paths.replay, handlers::replay)
            .build()
}
