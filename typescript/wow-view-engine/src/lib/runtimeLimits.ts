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

export interface RuntimeLimits {
  loadTimeoutMs: number;
  queryTimeoutMs: number;
  writeTimeoutMs: number;
  maxConcurrentQueries: number;
  maxRetainedResults: number;
  maxConfigBytes: number;
}
export interface RuntimeDiagnostic {
  operationId: string;
  kind: 'record' | 'analysis' | 'shared';
  operation: string;
  phase: 'started' | 'succeeded' | 'failed' | 'cancelled' | 'superseded';
  elapsedMs: number;
  errorCode?: string;
}
export class RuntimeLimitError extends Error {
  constructor(
    readonly code: 'TIMEOUT' | 'CANCELLED' | 'RESOURCE_LIMIT' | 'BUSY',
    message: string,
  ) {
    super(message);
    this.name = 'RuntimeLimitError';
  }
}
export function validateRuntimeLimits(
  input: Partial<RuntimeLimits> = {},
): Readonly<RuntimeLimits> {
  const limits = {
    loadTimeoutMs: 15000,
    queryTimeoutMs: 30000,
    writeTimeoutMs: 30000,
    maxConcurrentQueries: 4,
    maxRetainedResults: 5,
    maxConfigBytes: 262144,
    ...Object.fromEntries(
      Object.entries(input).filter(([, value]) => value !== undefined),
    ),
  };
  for (const [key, value] of Object.entries(limits))
    if (
      !Number.isSafeInteger(value) ||
      value <= 0 ||
      (key.endsWith('TimeoutMs') && value > 2147483647)
    )
      throw new TypeError(`运行预算 ${key} 必须为有效正整数`);
  return Object.freeze(limits);
}
export function assertConfigSize(config: unknown, maxBytes: number): void {
  const serialized = JSON.stringify(config);
  if (
    serialized === undefined ||
    new TextEncoder().encode(serialized).byteLength > maxBytes
  )
    throw new RuntimeLimitError(
      'RESOURCE_LIMIT',
      '配置大小超出运行预算，请减少配置内容',
    );
}
/** Abort-aware local deadline; the host may continue, but its late completion cannot settle this operation. */
export function withDeadline<T>(
  operation: () => Promise<T> | T,
  timeoutMs: number,
  controller: AbortController,
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    let settled = false;
    const finish = (success: boolean, value: unknown) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      controller.signal.removeEventListener('abort', cancel);
      if (success) resolve(value as T);
      // 原样透传上层操作的拒绝值，包装成 Error 会改变对外的拒绝契约。
      // eslint-disable-next-line @typescript-eslint/prefer-promise-reject-errors
      else reject(value);
    };
    const cancel = () =>
      finish(false, new RuntimeLimitError('CANCELLED', '操作已取消'));
    const timer = setTimeout(() => {
      finish(false, new RuntimeLimitError('TIMEOUT', '操作超时，请重试'));
      controller.abort();
    }, timeoutMs);
    controller.signal.addEventListener('abort', cancel, { once: true });
    if (controller.signal.aborted) {
      cancel();
      return;
    }
    try {
      Promise.resolve(operation()).then(
        value => finish(true, value),
        error => finish(false, error),
      );
    } catch (error) {
      finish(false, error);
    }
  });
}
/** A shared engine budget with replacement ownership, no queue and no stale release. */
export class QueryBudget {
  private readonly owners = new Map<string, object>();
  constructor(private readonly maximum: number) {}
  acquire(key: string, token: object): () => void {
    if (!this.owners.has(key) && this.owners.size >= this.maximum)
      throw new RuntimeLimitError('BUSY', '查询并发已达上限，请稍后重试');
    this.owners.set(key, token);
    return () => {
      if (this.owners.get(key) === token) this.owners.delete(key);
    };
  }
}
export function reportDiagnostic(
  callback: ((event: RuntimeDiagnostic) => void) | undefined,
  event: RuntimeDiagnostic,
): void {
  try {
    callback?.(Object.freeze(event));
  } catch {
    /* Diagnostic observers cannot change operation outcomes. */
  }
}

/** One metadata-only lifecycle per operation, shared by all engine boundaries. */
export function beginDiagnostic(
  callback: ((event: RuntimeDiagnostic) => void) | undefined,
  kind: RuntimeDiagnostic['kind'],
  operation: string,
): (phase: RuntimeDiagnostic['phase'], errorCode?: string) => void {
  if (!callback) return () => {};
  const operationId = crypto.randomUUID(),
    started = performance.now();
  let finished = false;
  return (phase, errorCode) => {
    if (finished) return;
    if (phase !== 'started') finished = true;
    reportDiagnostic(callback, {
      operationId,
      kind,
      operation,
      phase,
      elapsedMs: performance.now() - started,
      ...(errorCode ? { errorCode } : {}),
    });
  };
}
