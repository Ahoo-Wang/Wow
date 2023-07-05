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

package me.ahoo.wow.webflux.route.appender

object CommandHeaders {
    const val AGGREGATE_VERSION = "Command-Aggregate-Version"
    const val WAIT_STAGE = "Command-Wait-Stage"
    const val WAIT_TIME_OUT = "Command-Wait-Timout"
    const val WAIT_CONTEXT = "Command-Wait-Context"
    const val WAIT_PROCESSOR = "Command-Wait-Processor"
    const val AGGREGATE_ID = "Command-Aggregate-Id"
    const val REQUEST_ID = "Command-Request-Id"
}
