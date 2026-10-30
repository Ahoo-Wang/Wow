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

import type {
  DatasetResource,
  RecordActions,
} from "@ahoo-wang/wow-view-engine";
import {
  bind,
  datasetBindings,
  type ViewBinding,
} from "@ahoo-wang/wow-view-engine/ui";
import { datasetDetail } from "@/detail/datasetDetail.tsx";
import { sourceDetail } from "@/detail/sourceDetail.tsx";
import type { CatalogCommands } from "./catalogCommands.ts";
import { datasetActions, type DatasetActionOptions } from "./datasetActions.ts";
import { BOARDS } from "./boards.ts";
import { DATASETS } from "./datasets.ts";
import { sourceActions } from "./sourceActions.ts";
import { SOURCES } from "./sources.ts";

/** Where the sources' views live. */
export const SOURCES_PATH = "/sources";

/** Where the datasets' views live. */
export const DATASETS_PATH = "/datasets";

/** Where a served dataset's rows are read. */
export const ROWS_PATH = "/rows";

/** Where the boards over the datasets live. */
export const BOARDS_PATH = "/boards";

/** Where a source or a dataset is drafted. */
export const DRAFT_PATH = "/draft";

/** Where the caller is named. */
export const CALLER_PATH = "/caller";

/** The open view of the workbench, in its address. */
export const VIEW_PARAM = "view";

/** The record open in a page's detail, in its address (the engine's). */
export const RECORD_PARAM = "id";

/** The served dataset the rows page reads, in its address. */
export const DATASET_PARAM = "dataset";

/** `path` on `view`, or on its default view (`null`), with `params`. */
function pathOf(
  path: string,
  view: string | null,
  params: Record<string, string> = {},
): string {
  const query = new URLSearchParams(params);
  if (view !== null) query.set(VIEW_PARAM, view);
  const search = query.toString();
  return search === "" ? path : `${path}?${search}`;
}

/** The sources' page on `view`. */
export function sourcesPath(view: string | null): string {
  return pathOf(SOURCES_PATH, view);
}

/** The datasets' page on `view`. */
export function datasetsPath(view: string | null): string {
  return pathOf(DATASETS_PATH, view);
}

/** The sources' page with the source `id` open. */
export function sourcePath(id: string): string {
  return pathOf(SOURCES_PATH, null, { [RECORD_PARAM]: id });
}

/** The datasets' page with the dataset `id` open. */
export function datasetPath(id: string): string {
  return pathOf(DATASETS_PATH, null, { [RECORD_PARAM]: id });
}

/** The boards' page on `board`, or on the first one (`null`). */
export function boardsPath(board: string | null): string {
  return pathOf(BOARDS_PATH, board);
}

/** The rows of the served dataset `dataset` (`{context}.{name}`) on `view`. */
export function rowsPath(dataset: string, view: string | null): string {
  return pathOf(ROWS_PATH, view, { [DATASET_PARAM]: dataset });
}

/**
 * The console's resources as it binds them (host-integration.md 4.2, 5):
 * the sources' and the datasets' pages, how one is read, and the data
 * owner's actions, which the engine places on every record view over them;
 * then each dataset served, read on the rows page, with the actions its rows
 * offer on the aggregates they refer to (`rowActions`). The data owner's
 * actions are offered as the caller's roles and the catalog's settings
 * allow (`access`).
 */
export function consoleBindings(
  commands: CatalogCommands,
  served: readonly DatasetResource[] = [],
  rowActions: (id: string) => RecordActions | undefined = () => undefined,
  access: DatasetActionOptions = {},
): ViewBinding[] {
  return [
    bind(SOURCES, {
      route: sourcesPath,
      reading: sourceDetail,
      actions: sourceActions(commands, access.roles),
    }),
    bind(DATASETS, {
      route: datasetsPath,
      reading: datasetDetail,
      actions: datasetActions(commands, access),
    }),
    ...datasetBindings(served, {
      route: ({ id }, view) => rowsPath(id, view),
      binding: ({ id }) => {
        const actions = rowActions(id);
        return actions ? { actions } : undefined;
      },
    }),
    // The boards: views of any dataset, of any service, side by side.
    bind(BOARDS, { route: boardsPath }),
  ];
}
