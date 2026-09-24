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
 * `@ahoo-wang/wow-client/legacy`: the deprecated Condition query model, for
 * Wow servers before 8.11, which understand nothing else.
 *
 * The query clients of the root entry accept the request shapes built here, so
 * an application that talks to an 8.10 server imports its queries from this
 * entry and everything else from the root. The whole entry is removed in v10;
 * see docs/compat-debt.md.
 */
export * from './condition.js';
export * from './operator.js';
export * from './queryable.js';
export * from './locale/operatorLocale.js';
export * from './locale/en_US.js';
export * from './locale/zh_CN.js';
