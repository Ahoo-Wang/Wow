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
package me.ahoo.wow.infra.accessor

import java.lang.reflect.AccessibleObject

/**
 * Accessor .
 *
 * @author ahoo wang
 */
interface Accessor {
    companion object {
        /**
         * When calling a static method, the instance object is null.
         */
        @JvmField
        val STATIC: Any? = null
    }
}

/**
 * ensure AccessibleObject is accessible.
 */
fun AccessibleObject.ensureAccessible() {
    if (!this.isAccessible) {
        this.isAccessible = true
    }
}
