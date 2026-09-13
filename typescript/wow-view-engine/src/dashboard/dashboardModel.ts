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

import type { FilterExpression } from '@ahoo-wang/fetcher-wow';
import type {
  FilterConfiguration,
  FilterJsonValue,
  FilterValidationError,
} from '../filter/filterModel.js';
import type { DeepReadonly } from '../lib/types.js';
import type {
  ViewDefinition,
  ViewInstance,
  ViewInstanceMetadata,
  RecordSession,
  ViewInstanceConflict,
} from '../contracts/viewModel.js';

interface DashboardPanelBase {
  id: string;
  layout: { x: number; y: number; w: number; h: number };
}
export interface DashboardViewPanel extends DashboardPanelBase {
  kind: 'view';
  instanceId: string;
}
export type DashboardContentPanel = DashboardPanelBase &
  (
    | { kind: 'markdown'; title: string; content: string }
    | { kind: 'link'; title: string; href: string; description?: string }
    | {
        kind: 'image';
        title: string;
        src: string;
        alt: string;
        caption?: string;
      }
  );
export type DashboardPanel = DashboardViewPanel | DashboardContentPanel;

export type DashboardBinding =
  | {
      panelId: string;
      kind: 'fields';
      fields: Record<string, string>;
      semanticCompatibility: true;
    }
  | {
      panelId: string;
      kind: 'transform';
      name: string;
      options?: Record<string, FilterJsonValue>;
    };
export interface DashboardFilter {
  id: string;
  filters: FilterConfiguration;
  bindings: DashboardBinding[];
  excludedPanelIds: string[];
}
export interface DashboardConfig {
  schemaVersion: 1;
  panels: DashboardPanel[];
  filters: DashboardFilter[];
}
export interface DashboardViewInstance extends ViewInstanceMetadata {
  kind: 'dashboard';
  config: DashboardConfig;
}
export interface DashboardSession {
  readonly positionId: string;
  readonly kind: 'dashboard';
  /** False denotes a local draft; baseline is its initial content, not a saved receipt. */
  readonly persisted: boolean;
  /** Runtime-only handoff from the local draft after authoritative creation. */
  readonly createdFromDraft?: string;
  readonly editorEpoch: number;
  readonly editorValidity: Readonly<Record<string, boolean>>;
  readonly editVersion: number;
  readonly validation: readonly FilterValidationError[];
  readonly baseline: DeepReadonly<DashboardViewInstance>;
  readonly instance: DeepReadonly<DashboardViewInstance>;
  readonly dirty: boolean;
  readonly writeStatus: RecordSession['writeStatus'];
  readonly writeError: string | null;
  readonly requiresReload: boolean;
  readonly conflict?: ViewInstanceConflict;
}
export interface DashboardTransformContext {
  expression: DeepReadonly<FilterExpression>;
  source: DeepReadonly<ViewDefinition>;
  target: DeepReadonly<ViewDefinition>;
  instance: DeepReadonly<ViewInstance>;
  options?: Readonly<Record<string, FilterJsonValue>>;
}
export type DashboardTransform = (
  context: DashboardTransformContext,
) => FilterExpression;
export type DashboardTransforms = Readonly<Record<string, DashboardTransform>>;
