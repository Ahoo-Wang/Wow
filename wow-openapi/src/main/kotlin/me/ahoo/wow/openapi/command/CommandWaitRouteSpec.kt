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

package me.ahoo.wow.openapi.command

import me.ahoo.wow.api.Wow
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.openapi.AbstractRouteSpec
import me.ahoo.wow.openapi.Https

object CommandWaitRouteSpec : AbstractRouteSpec() {

    override val id: String
        get() = "wow.command.wait"
    override val path: String
        get() = "/${Wow.WOW}/command/wait"
    override val method: String
        get() = Https.Method.POST
    override val summary: String
        get() = "command wait handler"
    override val description: String
        get() = ""
    override val requestBodyType: Class<*>
        get() = WaitSignal::class.java

    init {
        super.build()
    }
}
