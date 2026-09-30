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

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * The standalone view store: the view store starter on MongoDB, for a business that has no Wow service of its own
 * or keeps its views in one place. Its own context is not `view-store`, so the routes carry the `/view-store`
 * prefix, as they do in a host service.
 */
@SpringBootApplication
class ViewStoreServer

fun main(args: Array<String>) {
    runApplication<ViewStoreServer>(*args)
}
