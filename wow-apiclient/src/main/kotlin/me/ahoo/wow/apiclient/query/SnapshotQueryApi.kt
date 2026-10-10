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

import me.ahoo.wow.rest.RouteSuffixes

/**
 * [RouteSuffixes.SNAPSHOT]. This and the other `SNAPSHOT_*_RESOURCE_NAME` constants are names for the
 * [RouteSuffixes] the server's routes use, so the client's paths are the server's.
 */
const val SNAPSHOT_RESOURCE_NAME = RouteSuffixes.SNAPSHOT

interface SnapshotQueryApi
