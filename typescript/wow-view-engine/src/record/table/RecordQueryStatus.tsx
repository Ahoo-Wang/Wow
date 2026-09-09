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

import { CircleAlertIcon, InboxIcon } from 'lucide-react';
import { Button } from '../../components/ui/button.js';
import { Spinner } from '../../components/ui/spinner.js';
import { TableCell, TableRow } from '../../components/ui/table.js';
import { cn } from '../../lib/utils.js';
import type { RecordTableProps } from '../recordReactTypes.js';

export function RecordQueryStatus({
  querying,
  queryError,
  onQueryRetry,
  empty,
  columnSpan,
  availableWidth,
}: Pick<RecordTableProps, 'querying' | 'queryError' | 'onQueryRetry'> & {
  empty: boolean;
  columnSpan: number;
  availableWidth: number;
}) {
  const failure =
    !querying && queryError ? (
      <div
        role="alert"
        aria-label="查询失败"
        className="fve:flex fve:flex-wrap fve:items-center fve:justify-center fve:gap-2 fve:p-3 fve:text-sm fve:text-destructive"
      >
        <CircleAlertIcon
          aria-hidden="true"
          className="fve:size-5 fve:shrink-0"
        />
        <span className="fve:break-words">{queryError}</span>
        {!empty && (
          <span className="fve:text-muted-foreground">显示上次查询结果</span>
        )}
        {onQueryRetry && (
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={onQueryRetry}
          >
            重试查询
          </Button>
        )}
      </div>
    ) : null;
  if (!empty && !failure) return null;
  return (
    <TableRow>
      <TableCell
        colSpan={columnSpan}
        className={empty ? 'fve:h-24 fve:text-center' : undefined}
      >
        <div
          className={cn(
            'fve:sticky fve:left-0 fve:max-w-full',
            empty && 'fve:flex fve:justify-center',
          )}
          style={{
            width: availableWidth
              ? Math.max(0, availableWidth - 24)
              : undefined,
          }}
        >
          {empty && querying ? (
            <Spinner aria-label="正在加载记录" />
          ) : failure ? (
            failure
          ) : (
            <InboxIcon
              role="img"
              aria-label="暂无记录"
              className="fve:size-6 fve:text-muted-foreground"
            />
          )}
        </div>
      </TableCell>
    </TableRow>
  );
}
