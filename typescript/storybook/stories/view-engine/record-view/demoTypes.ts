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

import type { StoryObj } from '@storybook/react-vite';
import type { CursorQuery, PagedQueryRequest } from '@ahoo-wang/fetcher-wow';
import type {
  FilterConfiguration,
  ViewInstance,
} from '@ahoo-wang/fetcher-view-engine';

export interface DemoArgs {
  appearance: 'light' | 'dark';
}

export interface ScenarioOptions {
  mode?: 'paged' | 'cursor';
  empty?: boolean;
  failFirstQuery?: boolean;
  local?: boolean;
  summaries?: boolean;
  failFirstSummary?: boolean;
  failFirstDelete?: boolean;
  saveOnly?: boolean;
  pageSize?: number;
  sidebarCollapsed?: boolean;
  initialFilter?: FilterConfiguration;
}

export type DemoQuery = PagedQueryRequest | CursorQuery;

export interface QueryDiagnostic {
  calls: number;
  method: 'paged' | 'cursor' | null;
  request: DemoQuery | null;
}

export interface WriteDiagnostic {
  saves: number;
  creates: number;
  deletes: number;
  renames: number;
  instance: ViewInstance | null;
}

export type Story = StoryObj<DemoArgs>;

export type RecordViewPlay = NonNullable<Story['play']>;
