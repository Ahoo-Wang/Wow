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
 * The dashboard kernel: admission, panel binding resolution and the global
 * filter merge.
 *
 * It owns no data of its own. A dashboard composes instances of the other two
 * kinds, so what this layer decides is whether a composition holds together —
 * the references exist, they are visible where the dashboard is, and the
 * global filter maps onto every one of them without losing its meaning.
 */
export * from './defaults.js';
export * from './edit.js';
export * from './layout.js';
export * from './merge.js';
export * from './migrate.js';
export * from './panels.js';
export * from './tabs.js';
export * from './validate.js';
