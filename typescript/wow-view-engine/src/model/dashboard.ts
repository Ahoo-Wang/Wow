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

import type { FieldKindId, FieldOption } from './field.js';
import type { ViewConfigBase } from './config.js';

/**
 * A dashboard's own filter field. It spans definitions, so it is declared by
 * the config rather than by one definition.
 */
export interface DashboardField {
  name: string;
  label: string;
  kind: FieldKindId;
  options?: FieldOption[];
}

export interface DashboardPanelBase {
  id: string;
  title?: string;
  layout: PanelLayout;
}

/**
 * Columns of the dashboard grid. A panel's `x + w` stays within it, otherwise
 * the layout adapter would wrap or drop the panel instead of placing it.
 */
export const DASHBOARD_GRID_COLUMNS = 12;

/** Characters one markdown panel may hold; a config arrives from a store. */
export const MAX_MARKDOWN_LENGTH = 20_000;

/** Links one links panel may hold. */
export const MAX_PANEL_LINKS = 50;

/** Grid geometry; coordinates are non-negative and sizes positive integers. */
export interface PanelLayout {
  x: number;
  y: number;
  w: number;
  h: number;
}

/** Data panel: references a record or analysis instance and is filtered. */
export interface DashboardViewPanel extends DashboardPanelBase {
  kind: 'view';
  instanceId: string;
  bindings: PanelBinding[];
}

/** Maps a dashboard field onto a field of the referenced definition. */
export interface PanelBinding {
  globalField: string;
  panelField: string;
}

/** Content panels are static: no query, no global filter, no child runtime. */
export type DashboardContentPanel = DashboardPanelBase &
  (
    | { kind: 'markdown'; content: string }
    | {
        kind: 'image';
        src: string;
        alt?: string;
        fit?: 'contain' | 'cover';
        href?: string;
      }
    | { kind: 'links'; items: DashboardLink[] }
  );

export interface DashboardLink {
  label: string;
  href: string;
  description?: string;
}

export type DashboardPanel = DashboardViewPanel | DashboardContentPanel;

export interface DashboardViewConfig extends ViewConfigBase {
  kind: 'dashboard';
  fields: DashboardField[];
  panels: DashboardPanel[];
}

/**
 * A dashboard config has no presentation-only member of its own: every one
 * of `fields` and `panels` reaches the panels' queries or what they draw
 * from them (`PRESENTATION_MEMBERS` in `config.ts`).
 */
export const DASHBOARD_PRESENTATION_MEMBERS =
  [] as const satisfies readonly (keyof DashboardViewConfig)[];
