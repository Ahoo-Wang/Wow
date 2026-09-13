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
import { ChartColumnIcon, Table2Icon, LayoutDashboardIcon } from 'lucide-react';
import type { ViewSession } from '../contracts/viewModel.js';

export function ViewKindIcon({ kind }: { kind: ViewSession['kind'] }) {
  const Icon =
    kind === 'dashboard'
      ? LayoutDashboardIcon
      : kind === 'analysis'
        ? ChartColumnIcon
        : Table2Icon;
  return (
    <span
      aria-hidden="true"
      title={
        kind === 'dashboard'
          ? '仪表盘'
          : kind === 'analysis'
            ? '分析视图'
            : '数据视图'
      }
      className="fve:inline-flex fve:shrink-0"
    >
      <Icon className="fve:size-4" />
    </span>
  );
}
