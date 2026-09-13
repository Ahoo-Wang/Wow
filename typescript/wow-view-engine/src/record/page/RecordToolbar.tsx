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

import type { FieldSort } from '@ahoo-wang/fetcher-wow';
import { RecordSortSettings } from './RecordSortSettings.js';
import { useRef } from 'react';
import { Button } from '../../components/ui/button.js';
import { RecordCardSettings } from '../RecordCardSettings.js';
import { RecordColumnSettings } from '../RecordColumnSettings.js';
import type {
  RecordColumn,
  RecordCardConfig,
  RecordSession,
  RecordViewDefinition,
} from '../../contracts/viewModel.js';
import type { RecordExtensions } from '../recordReactTypes.js';
import { RecordActions } from './RecordActions.js';

export function RecordToolbar({
  definition,
  session,
  extensions,
  selectable,
  configurable = true,
  isCurrent,
  refresh,
  onSelectionClear,
  onColumnsChange,
  onCardChange,
  onSortChange,
}: {
  definition: RecordViewDefinition;
  session: RecordSession;
  extensions?: RecordExtensions;
  selectable: boolean;
  configurable?: boolean;
  isCurrent?(): boolean;
  refresh(): Promise<void>;
  onSelectionClear(): void;
  onColumnsChange(columns: RecordColumn[]): void;
  onCardChange?(card: RecordCardConfig): void;
  onSortChange(sort: FieldSort[]): void;
}) {
  const tableToolbarRef = useRef<HTMLDivElement>(null);
  const { instance } = session;
  const id = instance.id;
  return (
    <div
      role="group"
      aria-label="记录工具栏"
      data-slot="record-toolbar"
      ref={tableToolbarRef}
      tabIndex={-1}
      className="fve:flex fve:flex-wrap fve:items-center fve:justify-between fve:gap-[var(--fve-toolbar-gap)] fve:border-t fve:px-[var(--fve-toolbar-padding-x)] fve:py-[var(--fve-toolbar-padding-y)]"
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
          kind="toolbar"
          isCurrent={isCurrent}
          definition={definition}
          session={session}
          extensions={extensions}
          refresh={refresh}
        />
        {configurable && (
          <RecordSortSettings
            key={`sort:${id}:${session.editorEpoch}`}
            definition={definition}
            sort={instance.config.sort}
            onChange={onSortChange}
          />
        )}
        {configurable &&
          (instance.config.presentation.layout === 'table' ? (
            <RecordColumnSettings
              key={`columns:${id}:${session.editorEpoch}`}
              definition={definition}
              columns={instance.config.presentation.table.columns}
              onChange={onColumnsChange}
            />
          ) : onCardChange ? (
            <RecordCardSettings
              key={`card:${id}:${session.editorEpoch}`}
              definition={definition}
              card={instance.config.presentation.card}
              onChange={onCardChange}
            />
          ) : null)}
      </div>
    </div>
  );
}
