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
import { sourceIssue } from '../runtime/issues.js';
import { useViewRuntime } from './useViewEngine.js';

export interface RecordDetailController {
  /** The record open in the detail, or `null` while none is. */
  key: RecordKey | null;
  /**
   * What the detail shows: the whole record once it has come, and the row
   * the page already had until then — so the detail opens at once, with what
   * was on screen, and fills in rather than blinking empty first.
   */
  record: RecordData | null;
  /** Whether `record` is the whole record rather than the page's row. */
  complete: boolean;
  /** Reading the whole record. */
  loading: boolean;
  /** Why the whole record could not be read, in the source's words. */
  error: Issue | null;
  /** The record is no longer there: deleted, or out of the injected scope. */
  missing: boolean;
  /** How the detail is laid out: the definition's groups (`detailSections`). */
  sections: readonly DetailSection[];
  open(row: RecordRow): void;
  close(): void;
}

interface Opened {
  key: RecordKey;
  row: RecordData;
  whole: RecordData | null;
  error: Issue | null;
  missing: boolean;
  /**
   * Which of the view's results the last read answered, boxed so that "no
   * result yet" is an answer too; `null` until a read has answered at all.
   * The detail is loading while it answers an older one than the latest.
   */
  answered: { landed: number | undefined } | null;
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
 */
export function useRecordDetail(
  runtime: RecordViewRuntime | null,
): RecordDetailController {
  const state = useViewRuntime(runtime);
  const [opened, setOpened] = useState<Opened | null>(null);
  const key = opened?.key ?? null;
  const landed = state?.result?.receivedAt;

  useEffect(() => {
    if (!runtime || key === null) return;
    const controller = new AbortController();
    const answered = { landed };
    runtime.fetchRecord(key, controller.signal).then(
      whole => {
        if (controller.signal.aborted) return;
        setOpened(was =>
          was && was.key === key
            ? {
                ...was,
                whole,
                error: null,
                missing: whole === null,
                answered,
              }
            : was,
        );
      },
      async (failure: unknown) => {
        if (controller.signal.aborted) return;
        const error = await sourceIssue(failure, 'record.detail.failed');
        if (controller.signal.aborted) return;
        setOpened(was =>
          was && was.key === key ? { ...was, error, answered } : was,
        );
      },
    );
    return () => controller.abort();
  }, [runtime, key, landed]);

  const open = useCallback((row: RecordRow) => {
    setOpened({
      key: row.key,
      row: row.data,
      whole: null,
      error: null,
      missing: false,
      answered: null,
    });
  }, []);
  const close = useCallback(() => setOpened(null), []);

  return {
    key,
    record: opened ? (opened.whole ?? opened.row) : null,
    complete: opened?.whole != null,
    loading:
      opened !== null &&
      (opened.answered === null || opened.answered.landed !== landed),
    error: opened?.error ?? null,
    missing: opened?.missing ?? false,
    sections: runtime ? detailSections(runtime.definition) : [],
    open,
    close,
  };
}
