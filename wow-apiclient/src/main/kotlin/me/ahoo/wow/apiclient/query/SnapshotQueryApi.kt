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

package me.ahoo.wow.apiclient.query

const val SNAPSHOT_RESOURCE_NAME = "snapshot"
const val SNAPSHOT_SINGLE_RESOURCE_NAME = "$SNAPSHOT_RESOURCE_NAME/single"
const val SNAPSHOT_SINGLE_STATE_RESOURCE_NAME = "$SNAPSHOT_SINGLE_RESOURCE_NAME/state"
const val SNAPSHOT_LIST_RESOURCE_NAME = "$SNAPSHOT_RESOURCE_NAME/list"
const val SNAPSHOT_LIST_STATE_RESOURCE_NAME = "$SNAPSHOT_LIST_RESOURCE_NAME/state"
const val SNAPSHOT_PAGED_QUERY_RESOURCE_NAME = "$SNAPSHOT_RESOURCE_NAME/paged"
const val SNAPSHOT_PAGED_QUERY_STATE_RESOURCE_NAME = "$SNAPSHOT_PAGED_QUERY_RESOURCE_NAME/state"
const val SNAPSHOT_COUNT_RESOURCE_NAME = "$SNAPSHOT_RESOURCE_NAME/count"

interface SnapshotQueryApi
