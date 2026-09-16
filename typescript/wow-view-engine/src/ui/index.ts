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
 * The `/ui` entry: the default look, built on shadcn/ui with Base UI
 * primitives. Every component here consumes a controller from `/react` and
 * nothing else, so replacing one is a matter of writing different markup
 * against the same contract.
 *
 * Styles ship separately as `@ahoo-wang/fetcher-view-engine/styles.css`.
 */
export * from './FilterPanel.js';
export * from './FilterValueEditor.js';
export * from './RecordCards.js';
export * from './RecordTable.js';
export * from './RecordToolbar.js';
export * from './RecordWorkbench.js';
export * from './SaveActions.js';
export * from './ViewList.js';
export * from './ViewSurface.js';
