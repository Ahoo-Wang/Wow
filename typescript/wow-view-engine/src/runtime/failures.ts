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

import type { ViewStore } from '../store/ViewStore.js';
import type { ViewRuntime } from './viewRuntimeTypes.js';
import type {
  RuntimeEnvironment,
  ViewErrorContext,
  ViewErrorEvent,
  ViewErrorKind,
} from './environment.js';

/** Where a failure happened, less what it was doing. */
export type FailurePlace = Omit<ViewErrorContext, 'operation'>;

/**
 * Hands one failure to the host's `onError` (D40), and nothing else: no
 * console, no rethrow. The hook is the host's code running inside the
 * engine's own paths — a query settling, a write being recorded, a boundary
 * catching — so whatever it throws, or a promise it returns rejects with,
 * is dropped here rather than taking that path down with it.
 */
export function reportError(
  environment: Pick<RuntimeEnvironment, 'onError'> | undefined,
  event: ViewErrorEvent,
): void {
  const onError = environment?.onError;
  if (!onError) return;
  try {
    const returned: unknown = onError.call(environment, event);
    if (isThenable(returned)) returned.then(undefined, () => undefined);
  } catch {
    // The host's own failure is the host's: the engine carries on.
  }
}

function isThenable(value: unknown): value is PromiseLike<unknown> {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as { then?: unknown }).then === 'function'
  );
}

/**
 * Whether a rejection is a request being called off — by its own signal, or
 * as an `AbortError` — rather than a failure. Called off is what typing
 * does, and closing a view: nobody is told.
 */
export function isCalledOff(error: unknown, signal?: AbortSignal): boolean {
  if (signal?.aborted) return true;
  return (
    typeof error === 'object' &&
    error !== null &&
    (error as { name?: unknown }).name === 'AbortError'
  );
}

/** Tells the host of one failure of a kind already chosen. */
export type FailureReporter = (
  operation: string,
  error: unknown,
  place?: FailurePlace,
) => void;

/**
 * A reporter for one kind, with what is known of where it runs read at the
 * moment of the failure — a view saved since it opened says its id.
 */
export function failureReporter(
  environment: Pick<RuntimeEnvironment, 'onError'>,
  kind: ViewErrorKind,
  known: () => FailurePlace = () => ({}),
): FailureReporter {
  return (operation, error, place) =>
    reportError(environment, {
      kind,
      error,
      context: { operation, ...known(), ...place },
    });
}

/**
 * The store as the engine calls it: every rejection of every call is told
 * to the host once, as a `store` failure. It is said here, at the one door
 * every read and write passes through, so a failure the list, the write
 * ledger, a board's reference and the tab memory each answer in their own
 * way is still said exactly once, and no caller has to remember to. A call
 * aborted by its own signal is not a failure. `permissions` is a synchronous
 * answer rather than a request, and is passed on as it is.
 */
export function reportingStore(
  store: ViewStore,
  environment: Pick<RuntimeEnvironment, 'onError'>,
): ViewStore {
  const report = failureReporter(environment, 'store');
  const watched = <T>(
    call: () => Promise<T>,
    operation: string,
    place: FailurePlace,
    signal?: AbortSignal,
  ): Promise<T> => {
    const failed = (error: unknown): never => {
      if (!isCalledOff(error, signal)) report(operation, error, place);
      throw error;
    };
    // A store that throws before handing back a promise has failed too.
    let pending: Promise<T>;
    try {
      pending = call();
    } catch (error) {
      return failed(error);
    }
    return pending.catch(failed);
  };
  return {
    list: (definitionId, signal) =>
      watched(
        () => store.list(definitionId, signal),
        'list',
        { definitionId },
        signal,
      ),
    get: (id, signal) =>
      watched(() => store.get(id, signal), 'get', { instanceId: id }, signal),
    create: (input, context) =>
      watched(() => store.create(input, context), 'create', {
        definitionId: input.definitionId,
        requestId: context.requestId,
      }),
    save: (id, config, revision, context) =>
      watched(() => store.save(id, config, revision, context), 'save', {
        instanceId: id,
        requestId: context.requestId,
      }),
    rename: (id, title, revision, context) =>
      watched(() => store.rename(id, title, revision, context), 'rename', {
        instanceId: id,
        requestId: context.requestId,
      }),
    delete: (id, revision, context) =>
      watched(() => store.delete(id, revision, context), 'delete', {
        instanceId: id,
        requestId: context.requestId,
      }),
    getPreferences: (definitionId, signal) =>
      watched(
        () => store.getPreferences(definitionId, signal),
        'getPreferences',
        { definitionId },
        signal,
      ),
    setPreferences: (definitionId, preferences, context) =>
      watched(
        () => store.setPreferences(definitionId, preferences, context),
        'setPreferences',
        { definitionId, requestId: context.requestId },
      ),
    ...(store.permissions
      ? { permissions: definitionId => store.permissions!(definitionId) }
      : {}),
  };
}

/**
 * Tells the host of a failure met beside an open view rather than inside
 * it — a file that could not be handed over, a picture that could not be
 * drawn — through the environment that view runs on, saying which view.
 * No view, no one to tell.
 */
export function reportViewFailure(
  runtime: ViewRuntime | null | undefined,
  kind: ViewErrorKind,
  operation: string,
  error: unknown,
): void {
  if (!runtime) return;
  reportError(runtime.environment, {
    kind,
    error,
    context: { operation, ...viewPlace(runtime) },
  });
}

/** Which view: its definition, its saved id if it has one, its runtime. */
export function viewPlace(runtime: ViewRuntime): FailurePlace {
  const saved = runtime.getSnapshot().saved;
  return {
    definitionId: runtime.definition.id,
    ...(saved ? { instanceId: saved.id } : {}),
    runtimeId: runtime.id,
  };
}
