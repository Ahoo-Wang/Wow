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

package me.ahoo.wow.migrate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs [read], a call that blocks (a read of the 9.x store through its synchronous driver, say), on the IO
 * dispatcher, so the coroutine that waits for it does not hold one of the default dispatcher's threads.
 */
internal suspend fun <T> blocking(read: () -> T): T = withContext(Dispatchers.IO) { read() }
