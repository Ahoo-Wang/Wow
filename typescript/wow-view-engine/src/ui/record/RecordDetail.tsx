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
import { createContext, useContext, useEffect, useRef } from 'react';
import { ArrowLeftIcon, ChevronRightIcon, RotateCcwIcon } from 'lucide-react';
import { cn } from 'cn';
import type { RecordKey } from '../../model/index.js';
import type { DetailSection, RecordRow } from '../../record/index.js';
import { recordValue } from '../../record/index.js';
import type {
  RecordDetailController,
  RecordDetailSection,
} from '../../react/index.js';
import { LineAlert } from '../alerts.js';
import { AlertAction, AlertTitle } from '../components/alert.js';
import { Button } from '../components/button.js';
import {
  Sheet,
  SheetClose,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '../components/sheet.js';
import { Skeleton } from '../components/skeleton.js';
import { useViewMessages } from '../MessagesProvider.js';
import { SheetContent } from '../popups.js';
import {
  RenderBoundary,
  RenderSlot,
  type RenderFailureHandler,
} from '../RenderBoundary.js';
import { RowActions } from '../RowActions.js';
import { takeStop } from '../roving.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { inRowCurrency } from '../currency.js';
import { cellValue } from './cells.js';
import { blockOf } from './DetailStructure.js';
import { placeSections } from './detailPlacement.js';

export interface RecordDetailProps {
  detail: RecordDetailController;
  /** The row's own commands, as the list offers them, in the panel's header. */
  actions?(row: RecordRow): ReactNode;
  /**
   * The host's own sections for the record open (`RecordDetailSection`),
   * placed among the engine's field groups. Asked only while a record is in
   * hand, and each drawn inside a boundary of its own.
   */
  sections?(row: RecordRow): readonly RecordDetailSection[];
  /** Told of a host section that threw, as the workbench's other parts are. */
  onRenderFailure?: RenderFailureHandler;
}

/**
 * The detail a host section is drawn inside: the record open there and the
 * section's title. A detail opened from something drawn in there (an
 * embed's row, G20) is a second layer: it reads this to say where it came
 * from and to offer the way back. The engine's own field groups hold
 * nothing that opens a detail, so only a host section provides it.
 */
interface DetailLayer {
  parent: RecordKey;
  section: string;
}

const DetailLayerContext = createContext<DetailLayer | null>(null);

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
 *
 * Opened by its key alone (a link to a record the page does not hold, G2),
 * it says that it is reading, then shows the record — or says that the
 * record is not there, that the reader may not see it, or that it could not
 * be read, with a way to try again.
 */
export function RecordDetail({
  detail,
  actions,
  sections: hostSections,
  onRenderFailure,
}: RecordDetailProps) {
  const messages = useViewMessages();
  const { key, record } = detail;
  const row: RecordRow | null =
    key !== null && record ? { key, data: record } : null;
  // Opening on the record's key, the way the view manager opens on its
  // heading: it is where a reader starts, and the first focusable thing
  // is the row's command menu, whose tooltip would sit over the very key
  // it names. Tab from the key is the commands.
  const heading = useRef<HTMLHeadingElement>(null);
  // Where focus goes back to on close: the record's row, when the page has
  // it — the row a press opened it from, and the row a link's record stands
  // on — or wherever focus was before, which is Base UI's own answer. The
  // key is remembered past the close, which a controlling host may already
  // have turned to `null` by the time focus is handed back.
  const anchor = useRef<HTMLSpanElement>(null);
  const last = useRef<RecordKey | null>(null);
  useEffect(() => {
    if (key !== null) last.current = key;
  }, [key]);
  const error = detail.error;
  // Opened from inside another detail: a sheet over a sheet. It stands in
  // from the one underneath, whose edge stays in sight (dimmed by the
  // stylesheet while this one is open), and trades the close button for a
  // way back named by the record underneath — the same close, said as
  // where it goes.
  const layer = useContext(DetailLayerContext);
  const nested = layer !== null;
  return (
    <>
      <span ref={anchor} hidden data-slot="record-detail-anchor" />
      <Sheet
        open={key !== null}
        onOpenChange={open => {
          if (!open) detail.close();
        }}
      >
        <SheetContent
          data-slot="record-detail"
          data-layer={nested ? 'nested' : undefined}
          aria-busy={detail.loading}
          initialFocus={heading}
          finalFocus={() => rowOf(anchor.current, last.current) ?? true}
          showCloseButton={!nested}
          className={cn(nested && 'sm:max-w-[33rem]')}
        >
          <SheetHeader className={cn('gap-2', !nested && 'pr-12')}>
            {layer ? (
              <div className="-ml-2 flex min-w-0 items-center gap-1">
                <SheetClose
                  data-slot="record-detail-back"
                  render={<Button variant="ghost" size="sm" />}
                  aria-label={messages.label('label.record.detail.back', {
                    key: String(layer.parent),
                  })}
                  className="min-w-0 font-mono"
                >
                  <ArrowLeftIcon data-icon="inline-start" />
                  <span className="truncate">{String(layer.parent)}</span>
                </SheetClose>
                <ChevronRightIcon
                  aria-hidden="true"
                  className="size-3.5 shrink-0 text-muted-foreground"
                />
                <SheetDescription className="truncate">
                  {layer.section}
                </SheetDescription>
              </div>
            ) : (
              <SheetDescription>
                {messages.label('label.record.detail')}
              </SheetDescription>
            )}
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
            {error && (
              <LineAlert
                tone="error"
                data-slot="record-detail-error"
                data-code={error.code}
              >
                <AlertTitle>
                  {messages.issue(error)}
                  {record &&
                    ` ${messages.label('label.record.detail.partial')}`}
                </AlertTitle>
                {/* A refusal is the reader's standing, not a hiccup: trying
                    again would only be refused again. */}
                {error.code !== 'record.detail.forbidden' && (
                  <AlertAction>
                    <Button
                      variant="outline"
                      size="xs"
                      data-slot="record-detail-retry"
                      disabled={detail.loading}
                      onClick={detail.reload}
                    >
                      <RotateCcwIcon data-icon="inline-start" />
                      {messages.label('label.record.detail.retry')}
                    </Button>
                  </AlertAction>
                )}
              </LineAlert>
            )}
            {detail.missing ? (
              <p
                data-slot="record-detail-missing"
                className="text-muted-foreground"
              >
                {messages.label('label.record.detail.missing')}
              </p>
            ) : row ? (
              placeSections(detail.sections, hostSections?.(row) ?? []).map(
                placed =>
                  placed.host ? (
                    <HostSection
                      key={`host:${placed.section.id}`}
                      parent={row.key}
                      section={placed.section}
                      onRenderFailure={onRenderFailure}
                    />
                  ) : (
                    <FieldSection
                      key={`fields:${placed.section.id ?? '\u0000other'}`}
                      section={placed.section}
                      detail={detail}
                    />
                  ),
              )
            ) : (
              detail.loading && <Reading />
            )}
            {detail.loading && (
              <p className="sr-only" role="status">
                {messages.label('label.record.detail.loading')}
              </p>
            )}
          </div>
        </SheetContent>
      </Sheet>
    </>
  );
}

/** One of the engine's sections: a field group of the definition. */
function FieldSection({
  section,
  detail,
}: {
  section: DetailSection;
  detail: RecordDetailController;
}) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  const record = detail.record;
  const heading = `record-detail-${section.id ?? 'other'}`;
  return (
    <section
      aria-labelledby={heading}
      data-slot="record-detail-section"
      className="flex flex-col gap-2"
    >
      <h3 id={heading} className="text-sm font-semibold">
        {section.label ?? messages.label('label.record.detail.other')}
      </h3>
      <dl className="grid grid-cols-[minmax(6rem,max-content)_minmax(0,1fr)] gap-x-4 gap-y-2">
        {section.fields.map(field => {
          const value = record ? recordValue(record, field.field) : undefined;
          const shown =
            value === undefined && detail.loading ? (
              <Skeleton className="h-4 w-24" />
            ) : (
              (cellValue(
                value,
                inRowCurrency(field, record ?? undefined),
                messages,
                display,
                'detail',
              ) ?? '—')
            );
          // A structure or a paragraph takes the whole width, under its
          // label (`blockOf`).
          const block = blockOf(value);
          return (
            <div key={field.field} className="contents">
              <dt
                className={cn('text-muted-foreground', block && 'col-span-2')}
              >
                {field.label}
              </dt>
              <dd
                data-block={block ? '' : undefined}
                className={cn('min-w-0 [overflow-wrap:anywhere]', block)}
              >
                {shown}
              </dd>
            </div>
          );
        })}
      </dl>
    </section>
  );
}

/**
 * One of the host's sections, headed and labelled the way the engine's are
 * — a reader moving by headings meets them in the same outline — and drawn
 * inside a boundary of its own, so a section that throws says so in its
 * place and leaves the record and the other sections standing.
 */
function HostSection({
  parent,
  section,
  onRenderFailure,
}: {
  parent: RecordKey;
  section: RecordDetailSection;
  onRenderFailure?: RenderFailureHandler;
}) {
  const heading = `record-detail-host-${section.id}`;
  return (
    <section
      aria-labelledby={heading}
      data-slot="record-detail-section"
      data-host=""
      data-section={section.id}
      className="flex flex-col gap-2"
    >
      <h3 id={heading} className="text-sm font-semibold">
        {section.title}
      </h3>
      <DetailLayerContext.Provider value={{ parent, section: section.title }}>
        <RenderBoundary name="detail" onFailure={onRenderFailure}>
          <RenderSlot render={section.render} />
        </RenderBoundary>
      </DetailLayerContext.Provider>
    </section>
  );
}

/**
 * What stands in the detail while a record opened by its key alone is read:
 * the shape of a section, never a blank panel. What it is doing is said to a
 * screen reader by the detail's status line.
 */
function Reading() {
  return (
    <div
      data-slot="record-detail-reading"
      aria-hidden="true"
      className="flex flex-col gap-2"
    >
      <Skeleton className="h-4 w-32" />
      <Skeleton className="h-4 w-full" />
      <Skeleton className="h-4 w-3/4" />
      <Skeleton className="h-4 w-2/3" />
    </div>
  );
}

/**
 * The row of `key` among the rows the detail was drawn beside — the result
 * the anchor sits in — made the rows' one Tab stop, so focus lands on the
 * row itself rather than on the first control inside it; `null` when that
 * page does not hold it.
 */
function rowOf(
  anchor: HTMLElement | null,
  key: RecordKey | null,
): HTMLElement | null {
  const scope = anchor?.parentElement;
  if (!scope || key === null) return null;
  const text = String(key);
  const rows = [...scope.querySelectorAll<HTMLElement>('[data-row-key]')];
  const row = rows.find(one => one.dataset.rowKey === text) ?? null;
  takeStop(row, rows);
  return row;
}
