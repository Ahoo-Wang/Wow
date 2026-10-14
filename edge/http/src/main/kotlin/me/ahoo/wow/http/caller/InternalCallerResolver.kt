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

package me.ahoo.wow.http.caller

/**
 * Resolves the callers of the internal port's call routes (decision D193, [me.ahoo.wow.http.callRoutes]) where the
 * public port's [CallerResolver] cannot: the internal port runs none of the application's web filters, so a resolver
 * that reads what such a filter put on the request (CoSec's security context) finds nothing there. This one
 * authenticates the request itself, such as CoSec's service principals from their tokens. The host takes it when the
 * application, or a module, gives one; the public port's resolver otherwise. Only a
 * [service][me.ahoo.wow.api.caller.Caller.service] identity it resolves is served.
 */
fun interface InternalCallerResolver : CallerResolver
