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

import { useCallback, useEffect, useState } from 'react';
import type { Issue, RecordData, RecordKey } from '../model/index.js';
import {
  detailSections,
  type DetailSection,
  type RecordRow,
} from '../record/index.js';
import type { RecordViewRuntime } from '../runtime/index.js';
import { recordReadIssue } from '../runtime/issues.js';
import { rowsOnScreen } from './recordSelection.js';
import { useViewRuntime } from './useViewEngine.js';

/**
 * Who says which record the detail has open, as `value` and `onChange` say
 * it on an input.
 *
 * Leaving `open` out lets the detail own it: a press on a row opens that
 * row, the close button closes it. Passing it — a key, or `null` for none —
 * puts the host in charge: every later value opens what it names, whether or
 * not that record is on the page, and a press or a close only asks
 * (`onOpenChange`), so a host that keeps the key in its address (`?id=`)
 * opens the same record from the link.
 */
export interface RecordDetailControl {
  /**
   * The record open in the detail, by its row key, or `null` for none. A key
   * is matched against the page's rows as it is (`===`): hand it over in the
   * type the rows carry it in.
   */
  open?: RecordKey | null;
  /**
   * Told whenever the reader opens a record (its key) or closes the detail
   * (`null`) — in either mode, so a host can follow the detail without owning
   * it. Not told when `open` itself changes: that is the host's own doing.
   */
  onOpenChange?(key: RecordKey | null): void;
}

export interface RecordDetailController {
  /** The record open in the detail, or `null` while none is. */
  key: RecordKey | null;
  /**
   * What the detail shows: the whole record once it has come, and the row
   * the page already had until then — so the detail opens at once, with what
   * was on screen, and fills in rather than blinking empty first. `null`
   * while a record opened by its key alone (a link to one not on the page)
   * has not been read yet, and after it could not be.
   */
  record: RecordData | null;
  /** Whether `record` is the whole record rather than the page's row. */
  complete: boolean;
  /** Reading the whole record. */
  loading: boolean;
  /**
   * Why the whole record could not be read, in the source's words:
   * `record.detail.forbidden` when the source refused the reader
   * (HTTP 401 or 403), `record.detail.failed` otherwise.
   */
  error: Issue | null;
  /** The record is no longer there: deleted, or out of the injected scope. */
  missing: boolean;
  /** How the detail is laid out: the definition's groups (`detailSections`). */
  sections: readonly DetailSection[];
  open(row: RecordRow): void;
  close(): void;
  /** Reads the whole record again — the way out of a failed read. */
  reload(): void;
}

interface Read {
  key: RecordKey;
  whole: RecordData | null;
  error: Issue | null;
  missing: boolean;
  /**
   * Which of the view's results the read answered, boxed so that "no result
   * yet" is an answer too. The detail is loading while it answers an older
   * one than the latest, or another attempt than the latest.
   */
  answered: { landed: number | undefined; attempt: number };
}

/** The page's row a record opened with, kept while that record stays open. */
interface Seed {
  key: RecordKey;
  row: RecordData;
}

/**
 * One record's detail (production review A2): what an operator opens to
 * read a failure whole — the message a column clipped, the stack trace no
 * column holds.
 *
 * The record is read on its own (`runtime.fetchRecord`), and read again each
 * time the view's result lands: a command run from the list or from the
 * detail refreshes the view, and the detail then says what the record is
 * now, not what it was when it was opened. A read that a newer one or a
 * close overtakes is dropped.
 *
 * A record may be opened by its key alone (`control.open`, G2): a link to
 * one the page does not hold. It then starts from the page's row when the
 * page has it, and from nothing — `loading`, then the record, `missing`, or
 * `error` — when it does not.
 */
export function useRecordDetail(
  runtime: RecordViewRuntime | null,
  control: RecordDetailControl = {},
): RecordDetailController {
  const state = useViewRuntime(runtime);
  const { open: held, onOpenChange } = control;
  const controlled = held !== undefined;
  const [own, setOwn] = useState<RecordKey | null>(null);
  const key = controlled ? held : own;
  const [seed, setSeed] = useState<Seed | null>(null);
  const [read, setRead] = useState<Read | null>(null);
  const [attempt, setAttempt] = useState(0);
  const landed = state?.result?.receivedAt;
  // A closed detail forgets what it read, so opening the same record again
  // starts from the page's row rather than from an old answer — a failure
  // or a "no longer there" that may no longer be true.
  if (key === null && read !== null) setRead(null);

  useEffect(() => {
    if (!runtime || key === null) return;
    const controller = new AbortController();
    const answered = { landed, attempt };
    runtime.fetchRecord(key, controller.signal).then(
      whole => {
        if (controller.signal.aborted) return;
        setRead({ key, whole, error: null, missing: whole === null, answered });
      },
      async (failure: unknown) => {
        if (controller.signal.aborted) return;
        const error = await recordReadIssue(failure);
        if (controller.signal.aborted) return;
        // A failed read keeps what an earlier one of the same record had.
        setRead(was => ({
          key,
          whole: was?.key === key ? was.whole : null,
          error,
          missing: false,
          answered,
        }));
      },
    );
    return () => controller.abort();
  }, [runtime, key, landed, attempt]);

  const open = useCallback(
    (row: RecordRow) => {
      setSeed({ key: row.key, row: row.data });
      if (!controlled) setOwn(row.key);
      onOpenChange?.(row.key);
    },
    [controlled, onOpenChange],
  );
  const close = useCallback(() => {
    if (!controlled) setOwn(null);
    onOpenChange?.(null);
  }, [controlled, onOpenChange]);
  const reload = useCallback(() => setAttempt(n => n + 1), []);

  const current = key !== null && read?.key === key ? read : null;
  const onPage =
    key === null
      ? undefined
      : state && rowsOnScreen(state)?.rows.find(row => row.key === key)?.data;
  const shown =
    key === null
      ? null
      : (current?.whole ??
        onPage ??
        (seed?.key === key ? seed.row : null) ??
        null);
  return {
    key,
    record: shown,
    complete: current?.whole != null,
    loading:
      key !== null &&
      (current === null ||
        current.answered.landed !== landed ||
        current.answered.attempt !== attempt),
    error: current?.error ?? null,
    missing: current?.missing ?? false,
    sections: runtime ? detailSections(runtime.definition) : [],
    open,
    close,
    reload,
  };
}
