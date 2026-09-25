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

/**
 * The Wow domain model as it crosses the wire: the mixin interfaces of
 * Kotlin's `wow-api` (`*Capable`, `TenantId`, `AggregateId`, …) and the
 * command results a client receives.
 *
 * The names follow `wow-api` one for one, and wow-generator maps the server's
 * schemas to them by name, so they keep Kotlin's two conventions: a name like
 * `TenantId`, `OwnerId`, `CommandId` or `RequestId` is an object holding that
 * field (`{ tenantId: string }`), not the value itself, and an object holding
 * a composite is `XxxCapable` (`AggregateIdCapable` holds an `AggregateId`).
 *
 * Types only, apart from the wire enums: nothing here imports a client, the
 * transport or a fetcher package.
 */
export * from './abac.js';
export * from './bi.js';
export * from './command.js';
export * from './common.js';
export * from './function.js';
export * from './messaging.js';
export * from './modeling.js';
export * from './naming.js';
