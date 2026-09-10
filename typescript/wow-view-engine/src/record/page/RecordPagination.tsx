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

import { ChevronLeftIcon, ChevronRightIcon } from 'lucide-react';
import { Button } from '../../components/ui/button.js';
import { FilterSelect } from '../../filter/FilterSelect.js';
import type { RecordSession } from '../recordModel.js';
import type { RecordPaginationPolicy } from './recordPaginationPolicy.js';

export function RecordPagination({
  session,
  policy,
  operations,
  run,
}: {
  session: RecordSession;
  policy: RecordPaginationPolicy;
  operations: {
    setPageSize(size: number): Promise<void>;
    nextPage(): Promise<void>;
    previousPage(): Promise<void>;
  };
  run(action: () => void | Promise<void>): void;
}) {
  if (session.queryError) return null;
  const { instance } = session;
  const querying = session.queryStatus === 'loading';
  const paged = policy.mode === 'paged';
  return (
    <nav
      aria-label="记录分页"
      data-slot="record-pagination"
      className="fve:flex fve:flex-wrap fve:items-center fve:justify-between fve:gap-[var(--fve-toolbar-gap)] fve:border-t fve:px-[var(--fve-toolbar-padding-x)] fve:py-[var(--fve-toolbar-padding-y)] fve:text-sm"
    >
      <div
        role="status"
        aria-busy={querying || undefined}
        className="fve:min-h-5 fve:text-muted-foreground"
      >
        {querying
          ? null
          : session.total !== null
            ? `共 ${session.total} 条记录`
            : `本页 ${session.rows.length} 条记录`}
      </div>
      <div className="fve:ml-auto fve:flex fve:flex-wrap fve:items-center fve:gap-x-4 fve:gap-y-2">
        <div className="fve:flex fve:items-center fve:gap-2">
          <span>每页</span>
          <FilterSelect
            label="每页记录数"
            value={String(instance.config.pagination.size)}
            onValueChange={size =>
              run(() => operations.setPageSize(Number(size)))
            }
            disabled={!policy.canChangePageSize}
            options={[
              ...new Set([10, 20, 50, 100, instance.config.pagination.size]),
            ]
              .sort((a, b) => a - b)
              .map(size => ({ value: String(size), label: `${size} 条` }))}
          />
        </div>
        <div className="fve:flex fve:items-center fve:gap-2">
          <span>
            {paged
              ? `第 ${session.page} / ${policy.pageCount ?? '–'} 页`
              : `第 ${session.page} 页`}
          </span>
          {paged && (
            <Button
              variant="outline"
              size="icon-sm"
              aria-label="上一页"
              disabled={!policy.canPrevious}
              onClick={() => run(operations.previousPage)}
            >
              <ChevronLeftIcon aria-hidden="true" />
            </Button>
          )}
          <Button
            variant="outline"
            size="icon-sm"
            aria-label="下一页"
            disabled={!policy.canNext}
            onClick={() => run(operations.nextPage)}
          >
            <ChevronRightIcon aria-hidden="true" />
          </Button>
        </div>
      </div>
    </nav>
  );
}
