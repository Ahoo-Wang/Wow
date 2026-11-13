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

package me.ahoo.wow.spring.boot.node

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.node.ServiceNodeOptions

/**
 * The options a node is built with, replaced: when a bean of it is present, its result over the options the host built
 * from its properties is what the node runs with; absent, nothing changes. A seam for measurements (the benchmark
 * server sets the append queue's and the snapshot writes' counts through it), not configuration: none of what it can
 * change is bound to a property, and making any of it one is a later decision with its own evidence.
 */
@InternalWowApi
fun interface NodeOptionsCustomizer {
    /** The options to build the node with, from those the host built ([options]). */
    fun customize(options: ServiceNodeOptions): ServiceNodeOptions
}
