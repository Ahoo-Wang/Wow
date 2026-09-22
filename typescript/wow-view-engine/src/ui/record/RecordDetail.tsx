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

import type { ReactNode } from 'react';
import { useRef } from 'react';
import type { RecordRow } from '../../record/index.js';
import { recordValue } from '../../record/index.js';
import type { RecordDetailController } from '../../react/index.js';
import { LineAlert } from '../alerts.js';
import {
  Sheet,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '../components/sheet.js';
import { Skeleton } from '../components/skeleton.js';
import { useViewMessages } from '../MessagesProvider.js';
import { SheetContent } from '../popups.js';
import { RowActions } from '../RowActions.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { cellValue } from './cells.js';

export interface RecordDetailProps {
  detail: RecordDetailController;
  /** The row's own commands, as the list offers them, in the panel's header. */
  actions?(row: RecordRow): ReactNode;
}

/**
 * One record read whole, beside the list it was opened from (production
 * review A2).
 *
 * It opens at once with what the list had, and fills in as the whole record
 * comes; every field the definition declares is listed under its group, in
 * the reading a column would give it — and a long value (an error message, a
 * stack trace) whole, kept as written, scrollable and copyable. The row's
 * commands sit in the header, so acting on what was just read does not mean
 * closing it to find the row again; the panel follows the record after a
 * command, because the controller reads it again when the view's result
 * lands.
 */
export function RecordDetail({ detail, actions }: RecordDetailProps) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  const { key, record } = detail;
  const row: RecordRow | null =
    key !== null && record ? { key, data: record } : null;
  // Opening on the record's key, the way the view manager opens on its
  // heading: it is where a reader starts, and the first focusable thing
  // is the row's command menu, whose tooltip would sit over the very key
  // it names. Tab from the key is the commands.
  const heading = useRef<HTMLHeadingElement>(null);
  return (
    <Sheet
      open={key !== null}
      onOpenChange={open => {
        if (!open) detail.close();
      }}
    >
      <SheetContent
        data-slot="record-detail"
        aria-busy={detail.loading}
        initialFocus={heading}
      >
        <SheetHeader className="gap-2 pr-12">
          <SheetDescription>
            {messages.label('label.record.detail')}
          </SheetDescription>
          <SheetTitle
            ref={heading}
            tabIndex={-1}
            className="font-mono break-all"
          >
            {key === null ? '' : String(key)}
          </SheetTitle>
          {row && actions && (
            <div data-slot="record-detail-actions" className="flex gap-2">
              <RowActions>{actions(row)}</RowActions>
            </div>
          )}
        </SheetHeader>
        <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto px-4 pb-4">
          {detail.error && (
            <LineAlert tone="error" data-slot="record-detail-error">
              {messages.issue(detail.error)}{' '}
              {messages.label('label.record.detail.partial')}
            </LineAlert>
          )}
          {detail.missing ? (
            <p
              data-slot="record-detail-missing"
              className="text-muted-foreground"
            >
              {messages.label('label.record.detail.missing')}
            </p>
          ) : (
            record &&
            detail.sections.map(section => {
              const heading = `record-detail-${section.id ?? 'other'}`;
              return (
                <section
                  key={section.id ?? '\u0000other'}
                  aria-labelledby={heading}
                  className="flex flex-col gap-2"
                >
                  <h3 id={heading} className="text-sm font-semibold">
                    {section.label ??
                      messages.label('label.record.detail.other')}
                  </h3>
                  <dl className="grid grid-cols-[minmax(6rem,max-content)_minmax(0,1fr)] gap-x-4 gap-y-2">
                    {section.fields.map(field => {
                      const value = recordValue(record, field.name);
                      const shown =
                        value === undefined && detail.loading ? (
                          <Skeleton className="h-4 w-24" />
                        ) : (
                          (cellValue(
                            value,
                            field,
                            messages,
                            display,
                            'detail',
                          ) ?? '—')
                        );
                      return (
                        <div key={field.name} className="contents">
                          <dt className="text-muted-foreground">
                            {field.label}
                          </dt>
                          <dd className="min-w-0 [overflow-wrap:anywhere]">
                            {shown}
                          </dd>
                        </div>
                      );
                    })}
                  </dl>
                </section>
              );
            })
          )}
          {detail.loading && (
            <p className="sr-only" role="status">
              {messages.label('label.record.detail.loading')}
            </p>
          )}
        </div>
      </SheetContent>
    </Sheet>
  );
}
