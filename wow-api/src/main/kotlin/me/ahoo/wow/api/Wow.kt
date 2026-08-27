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

import me.ahoo.wow.api.Wow.WOW

/**
 * Central configuration and constants object for the Wow framework.
 *
 * This object serves as a singleton container for framework-wide constants and version information.
 * It provides standardized naming conventions and version tracking for the Wow ecosystem components.
 *
 * @author ahoo wang
 */
object Wow {
    /**
     * The base name identifier for the Wow framework.
     * Used as a prefix for various naming conventions throughout the framework.
     */
    const val WOW: String = "wow"

    /**
     * The standard prefix used for Wow-related naming conventions.
     * Combines the base [WOW] name with a dot separator for hierarchical naming.
     */
    const val WOW_PREFIX: String = "$WOW."

    /**
     * The current version of the Wow framework.
     * This value is dynamically retrieved from the package implementation version at runtime.
     * If no version is available, returns an empty string.
     */
    val VERSION: String = Wow.javaClass.`package`.implementationVersion.orEmpty()
}
