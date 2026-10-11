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

import { bind, type ViewBinding } from '@ahoo-wang/wow-view-engine/ui';
import { BOARDS } from './engine.ts';

export const HOME_PATH = '/';
export const BOARDS_PATH = '/boards';
export const DATASETS_PATH = '/datasets';
export const ADMIN_PATH = '/admin';

/** The open view (or board) of a workbench page, in its address. */
export const VIEW_PARAM = 'view';

/** `path` on `view`, or on its default view (`null`). */
export function withView(path: string, view: string | null): string {
  return view === null
    ? path
    : `${path}?${new URLSearchParams({ [VIEW_PARAM]: view })}`;
}

export function datasetPath(id: string, view: string | null = null): string {
  return withView(`${DATASETS_PATH}/${encodeURIComponent(id)}`, view);
}

/**
 * Where each resource's views live: one workbench page per dataset (opening
 * a dataset is how a reader starts analysing it, D85), and the boards.
 */
export function analyticsBindings(datasets: readonly string[]): ViewBinding[] {
  return [
    ...datasets.map(id => bind(id, { route: view => datasetPath(id, view) })),
    bind(BOARDS.id, { route: view => withView(BOARDS_PATH, view) }),
  ];
}
