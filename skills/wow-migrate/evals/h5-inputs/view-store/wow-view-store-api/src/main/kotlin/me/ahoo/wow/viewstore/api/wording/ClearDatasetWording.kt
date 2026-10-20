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

package me.ahoo.wow.viewstore.api.wording

import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.api.command.DeleteAggregate

/**
 * Clears a dataset's wording, which then reads as none: the wording route's `DELETE` dispatches it with the expected
 * version. A command of the view store's own rather than Wow's `DefaultDeleteAggregate`, for the reason `DeleteView`
 * gives.
 */
@Summary("Clear the wording of a dataset")
@CommandRoute(enabled = false)
object ClearDatasetWording : DeleteAggregate
