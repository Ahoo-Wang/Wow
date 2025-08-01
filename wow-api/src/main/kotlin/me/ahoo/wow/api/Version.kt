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
package me.ahoo.wow.api

import com.fasterxml.jackson.annotation.JsonIgnore

/**
 * Version .
 *
 * @author ahoo wang
 */
interface Version {
    companion object {
        const val UNINITIALIZED_VERSION = 0
        const val INITIAL_VERSION = 1
    }

    val version: Int

    @get:JsonIgnore
    val initialized: Boolean
        get() {
            return version > UNINITIALIZED_VERSION
        }

    @get:JsonIgnore
    val isInitialVersion: Boolean
        get() {
            return version == INITIAL_VERSION
        }
}
