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

package me.ahoo.wow.rest

import me.ahoo.wow.api.Wow

/** The paths of Wow's global routes, which address no aggregate. */
object RoutePaths {
    const val COMMAND_WAIT = "/${Wow.WOW}/command/wait"
    const val COMMAND_SEND = "/${Wow.WOW}/command/send"
    const val METADATA = "/${Wow.WOW}/metadata"
    const val GLOBAL_ID = "/${Wow.WOW}/id/global"
    const val BI_SCRIPT = "/${Wow.WOW}/bi/script"

    /** A dataset, named by its bounded context and its name; the query operations follow it. */
    /** The dataset directory: every dataset this instance serves. */
    const val DATASETS = "/dataset"
    const val DATASET = "$DATASETS/{${RouteVariables.CONTEXT}}/{${RouteVariables.DATASET}}"
    const val DATASET_SCHEMA = "$DATASET/schema"
    const val DATASET_COUNT = "$DATASET/count"
    const val DATASET_AGGREGATION = "$DATASET/aggregation"
    const val DATASET_LIST = "$DATASET/list"
    const val DATASET_PAGED = "$DATASET/paged"
    const val DATASET_CURSOR = "$DATASET/cursor"
    const val DATASET_SINGLE = "$DATASET/single"

    /** The tenant prefix of a dataset route, as on an aggregate's routes. */
    const val TENANT_PREFIX = "/tenant/{${RouteVariables.TENANT_ID}}"
}
