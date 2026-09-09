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

import { useRef } from 'react';
import { Button } from '../../components/ui/button.js';
import { RecordColumnSettings } from '../RecordColumnSettings.js';
import type {
  RecordColumn,
  RecordSession,
  ViewDefinition,
} from '../recordModel.js';
import type { ViewExtensions } from '../recordReactTypes.js';
import { RecordActions } from './RecordActions.js';

export function RecordTableToolbar({
  definition,
  session,
  extensions,
  selectable,
  refresh,
  onSelectionClear,
  onColumnsChange,
}: {
  definition: ViewDefinition;
  session: RecordSession;
  extensions?: ViewExtensions;
  selectable: boolean;
  refresh(): Promise<void>;
  onSelectionClear(): void;
  onColumnsChange(columns: RecordColumn[]): void;
}) {
  const tableToolbarRef = useRef<HTMLDivElement>(null);
  const { instance } = session;
  const id = instance.id;
  return (
    <div
      role="group"
      aria-label="表格工具栏"
      ref={tableToolbarRef}
      tabIndex={-1}
      className="fve:flex fve:flex-wrap fve:items-center fve:justify-between fve:gap-2 fve:border-t fve:px-3 fve:py-2"
    >
      <div className="fve:flex fve:flex-wrap fve:items-center fve:gap-3">
        {selectable && session.selectedRowKeys.length > 0 && (
          <>
            <span
              role="status"
              className="fve:text-sm fve:text-muted-foreground"
            >
              已选本页 {session.selectedRowKeys.length} 条
            </span>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                onSelectionClear();
                tableToolbarRef.current?.focus();
              }}
            >
              取消选择
            </Button>
          </>
        )}
      </div>
      <div className="fve:ml-auto fve:flex fve:flex-wrap fve:items-center fve:gap-2">
        <RecordActions
          kind="table"
          definition={definition}
          session={session}
          extensions={extensions}
          refresh={refresh}
        />
        <RecordColumnSettings
          key={id}
          definition={definition}
          columns={instance.config.presentation.table.columns}
          onChange={onColumnsChange}
        />
      </div>
    </div>
  );
}
