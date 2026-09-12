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

import {
  StatefulViewHost,
  type StatefulViewHostOptions,
  type ViewStateTransaction,
} from './StatefulViewHost.js';
import { ViewServiceError } from '../contracts/viewServiceContract.js';
import { message } from '../lib/snapshot.js';
export interface IndexedDBViewHostOptions extends StatefulViewHostOptions {
  databaseName?: string;
}
/** Browser view persistence. Reads, CAS and writes commit in one native IndexedDB transaction. */
export class IndexedDBViewHost extends StatefulViewHost {
  constructor({ databaseName, ...options }: IndexedDBViewHostOptions) {
    super(options, indexedDBTransaction(databaseName));
  }
}

function indexedDBTransaction(
  databaseName = 'fve-view-state',
): ViewStateTransaction {
  return (key, operation, signal) =>
    new Promise((resolve, reject) => {
      let db: IDBDatabase | undefined;
      let transaction: IDBTransaction | undefined;
      let settled = false;
      const finish = (
        error?: unknown,
        result?: ReturnType<typeof operation>['result'],
      ) => {
        if (settled) return;
        settled = true;
        signal?.removeEventListener('abort', abort);
        db?.close();
        // error 可能是原始 abort 原因（signal.reason），原样透传以保持拒绝值不变。
        // eslint-disable-next-line @typescript-eslint/prefer-promise-reject-errors
        if (error !== undefined) reject(error);
        else resolve(result!);
      };
      const abort = () => {
        if (transaction) {
          try {
            transaction.abort();
          } catch {
            /* Commit may already have completed; oncomplete owns the result. */
          }
        } else
          finish(signal?.reason ?? new DOMException('Aborted', 'AbortError'));
      };
      if (signal?.aborted) {
        abort();
        return;
      }
      signal?.addEventListener('abort', abort, { once: true });
      try {
        const request = indexedDB.open(databaseName, 1);
        request.onupgradeneeded = () =>
          request.result.createObjectStore('states');
        request.onerror = () =>
          finish(new ViewServiceError('UNAVAILABLE', message(request.error)));
        request.onblocked = () =>
          finish(
            new ViewServiceError(
              'UNAVAILABLE',
              '视图数据库被旧连接阻塞，请关闭旧页面后重试',
            ),
          );
        request.onsuccess = () => {
          db = request.result;
          if (settled) {
            db.close();
            return;
          }
          db.onversionchange = () => db?.close();
          let result: ReturnType<typeof operation>['result'];
          let failure: unknown;
          try {
            // ponytail: one object store serializes view updates; use separate databases if write contention becomes material.
            transaction = db.transaction('states', 'readwrite');
            transaction.oncomplete = () => finish(undefined, result);
            transaction.onabort = () =>
              finish(
                failure ??
                  (signal?.aborted
                    ? signal.reason
                    : new ViewServiceError(
                        'UNAVAILABLE',
                        message(transaction?.error),
                      )),
              );
            const store = transaction.objectStore('states');
            const read = store.get(key);
            read.onsuccess = () => {
              try {
                signal?.throwIfAborted();
                // 该 store 仅存入字符串或空值，IDBRequest.result 的 any 收窄回契约类型。
                const next = operation(read.result as string | null);
                result = next.result;
                if (next.value !== undefined) {
                  try {
                    store.put(next.value, key);
                  } catch (error) {
                    throw new ViewServiceError('UNAVAILABLE', message(error));
                  }
                }
              } catch (error) {
                failure = error;
                transaction!.abort();
              }
            };
          } catch (error) {
            finish(new ViewServiceError('UNAVAILABLE', message(error)));
          }
        };
      } catch (error) {
        finish(new ViewServiceError('UNAVAILABLE', message(error)));
      }
    });
}
