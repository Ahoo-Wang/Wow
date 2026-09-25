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

import type { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';

/**
 * Reads a stream of server-sent events to its end and resolves to the `data`
 * of every event, in order.
 *
 * While it reads, it hands the rows received so far to `publish`, at most once
 * a macrotask: the rows of one network chunk arrive together, so a stream of
 * n rows costs a handful of renders rather than n, and `publish` always gets a
 * new array. It publishes the last rows before it resolves or rejects, so the
 * rows before a failure stay visible.
 *
 * `signal` aborting cancels the stream — a fake or already-buffered body
 * does not end with the request — and stops every later `publish`: a newer
 * query or an unmount has taken over, and its rows must not be overwritten.
 * The reader is always released, so the stream never stays locked.
 *
 * Internal: not exported from the package.
 */
export async function readStreamRows<R>(
  stream: ReadableStream<JsonServerSentEvent<R>>,
  signal: AbortSignal,
  publish: (rows: R[]) => void,
): Promise<R[]> {
  const reader = stream.getReader();
  const rows: R[] = [];
  let published = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const flush = () => {
    if (timer !== undefined) clearTimeout(timer);
    timer = undefined;
    if (signal.aborted || published === rows.length) return;
    published = rows.length;
    publish(rows.slice());
  };
  const cancel = () => {
    reader.cancel(signal.reason).catch(() => {
      // The stream had already failed; that failure is the one reported.
    });
  };
  if (signal.aborted) cancel();
  else signal.addEventListener('abort', cancel, { once: true });
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      rows.push(value.data);
      timer ??= setTimeout(flush, 0);
    }
    flush();
    return rows;
  } catch (error) {
    flush();
    throw error;
  } finally {
    if (timer !== undefined) clearTimeout(timer);
    signal.removeEventListener('abort', cancel);
    reader.releaseLock();
  }
}
