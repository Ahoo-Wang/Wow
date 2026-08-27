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

package me.ahoo.wow.spring.boot.starter

/**
 * Deterministic ordering for the built-in runtime components.
 *
 * Every component is prepared before any component starts, so these values do
 * not encode a dependency DAG. They define stable startup and reverse-cleanup
 * order for diagnostics and failure handling.
 */
object WowRuntimeComponentOrder {
    const val COMMAND = 100
    const val EVENT = 200
    const val PROJECTION = 300
    const val STATELESS_SAGA = 400
    const val SNAPSHOT = 500
}
