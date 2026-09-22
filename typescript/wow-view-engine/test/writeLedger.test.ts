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

import { describe, expect, it, vi } from 'vitest';
import {
  isViewCommandError,
  isViewWriteError,
  ViewStoreError,
  type ViewInstance,
  type ViewPreferences,
} from '../src/index.js';
import type { ManagedViewRuntime } from '../src/runtime/viewRuntime.js';
import type { ViewStore, WriteContext } from '../src/store/ViewStore.js';
import {
  WriteLedger,
  type WriteLedgerHost,
} from '../src/runtime/writeLedger.js';
import type { ViewChange } from '../src/runtime/viewChanges.js';
import { recordConfig } from './fixtures.js';

const mine: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'Mine',
  scope: 'personal',
  revision: '1',
  config: recordConfig(),
};

const preferences: ViewPreferences = {
  order: ['orders-1'],
  defaultInstanceId: null,
  revision: '1',
};

/**
 * The little of `ManagedViewRuntime` the ledger touches, with the same
 * "stopped means no-op" rule the real runtimes apply, so a test can dispose
 * an owner while its write is still out.
 */
interface FakeRuntime extends ManagedViewRuntime {
  dispose(): void;
}

function fakeRuntime(id = 'runtime-1'): FakeRuntime {
  const state: { write: { requestId: string } | null; saved: ViewInstance } = {
    write: null,
    saved: mine,
  };
  let stopped = false;
  const runtime = {
    id,
    getSnapshot: () => state,
    setWrite: vi.fn((write: { requestId: string } | null) => {
      if (!stopped) state.write = write;
    }),
    markSaved: vi.fn(),
    moveBaseline: vi.fn(),
    adoptSaved: vi.fn(),
    dispose: () => {
      stopped = true;
    },
  };
  return runtime as unknown as FakeRuntime;
}

interface Harness {
  ledger: WriteLedger;
  store: {
    save: ReturnType<typeof vi.fn>;
    get: ReturnType<typeof vi.fn>;
    getPreferences: ReturnType<typeof vi.fn>;
    setPreferences: ReturnType<typeof vi.fn>;
    delete: ReturnType<typeof vi.fn>;
  };
  noted: ViewInstance[];
  dropped: string[];
  notedPreferences: ViewPreferences[];
  reloaded: string[];
  changes: ViewChange[];
}

function harness(holders: ManagedViewRuntime[] = []): Harness {
  const store = {
    save: vi.fn(async () => ({ ...mine, revision: '2' })),
    get: vi.fn(async () => ({ ...mine, revision: '9' })),
    getPreferences: vi.fn(async () => preferences),
    setPreferences: vi.fn(async () => preferences),
    delete: vi.fn(async () => undefined),
  };
  const noted: ViewInstance[] = [];
  const dropped: string[] = [];
  const notedPreferences: ViewPreferences[] = [];
  const reloaded: string[] = [];
  const changes: ViewChange[] = [];
  let sequence = 0;

  const host: WriteLedgerHost = {
    store: store as unknown as ViewStore,
    newId: () => `request-${(sequence += 1)}`,
    noteInstance: instance => void noted.push(instance),
    dropInstance: id => void dropped.push(id),
    notePreferences: (_definitionId, next) => void notedPreferences.push(next),
    readPreferences: async definitionId => {
      reloaded.push(definitionId);
      return preferences;
    },
    holders: () => holders,
    noteChange: change => void changes.push(change),
  };

  return {
    ledger: new WriteLedger(host),
    store,
    noted,
    dropped,
    notedPreferences,
    reloaded,
    changes,
  };
}

function savePayload(revision = mine.revision) {
  return {
    action: 'save' as const,
    id: mine.id,
    revision,
    config: recordConfig(),
  };
}

/** The `requestId` each call to the store carried, in order. */
function requestIds(call: ReturnType<typeof vi.fn>): string[] {
  return call.mock.calls.map(
    args => (args[args.length - 1] as WriteContext).requestId,
  );
}

async function expectWriteError(promise: Promise<unknown>) {
  const error = await promise.catch((thrown: unknown) => thrown);
  if (!isViewWriteError(error)) throw new Error('expected a ViewWriteError');
  return error;
}

async function expectCommandError(promise: Promise<unknown>) {
  const error = await promise.catch((thrown: unknown) => thrown);
  if (!isViewCommandError(error))
    throw new Error('expected a ViewCommandError');
  return error;
}

describe('WriteLedger replaying', () => {
  it('mints one request id per logical write', async () => {
    const { ledger, store } = harness();
    await ledger.dispatch(savePayload(), undefined);
    await ledger.dispatch(savePayload(), undefined);
    expect(requestIds(store.save)).toEqual(['request-1', 'request-2']);
  });

  it('retries the same payload under the original request id', async () => {
    const { ledger, store } = harness();
    const runtime = fakeRuntime();
    store.save.mockRejectedValueOnce(new ViewStoreError('UNAVAILABLE', 'down'));

    const failure = await expectWriteError(
      ledger.dispatch(savePayload(), runtime),
    );
    expect(failure.state.kind).toBe('unknown');
    expect(runtime.setWrite).toHaveBeenCalledWith(failure.state);

    await ledger.retryWrite(failure.handle);
    // The same intent, so the same key: this is what lets a server that saw
    // the first attempt deduplicate the second.
    expect(requestIds(store.save)).toEqual(['request-1', 'request-1']);
    expect(store.save.mock.calls[1][1]).toEqual(savePayload().config);
    expect(ledger.pendingWrites().size).toBe(0);
  });

  it('addresses a write through its owning runtime as well as its handle', async () => {
    const { ledger, store } = harness();
    const runtime = fakeRuntime();
    store.save.mockRejectedValueOnce(new ViewStoreError('UNAVAILABLE', 'down'));

    await expectWriteError(ledger.dispatch(savePayload(), runtime));
    await ledger.retryWrite(runtime);
    expect(requestIds(store.save)).toEqual(['request-1', 'request-1']);
  });

  it('refuses a recovery action when nothing is pending', async () => {
    const { ledger } = harness();
    const error = await expectCommandError(ledger.retryWrite({ id: 'nobody' }));
    expect(error.issue.code).toBe('view.write.not-pending');
  });
});

describe('WriteLedger unknown outcomes', () => {
  it('holds a new intent back while the last one may have landed', async () => {
    const { ledger, store } = harness();
    const runtime = fakeRuntime();
    store.save.mockRejectedValueOnce(new ViewStoreError('UNAVAILABLE', 'down'));

    await expectWriteError(ledger.dispatch(savePayload(), runtime));
    const error = await expectCommandError(
      ledger.dispatch(savePayload(), runtime),
    );
    expect(error.issue.code).toBe('view.write.unknown-pending');
    expect(store.save).toHaveBeenCalledTimes(1);
  });

  it('holds a write with no runtime back by the instance it names', async () => {
    const { ledger, store } = harness();
    store.save.mockRejectedValueOnce(new ViewStoreError('UNAVAILABLE', 'down'));

    await expectWriteError(ledger.dispatch(savePayload(), undefined));
    const error = await expectCommandError(
      ledger.dispatch(savePayload(), undefined),
    );
    expect(error.issue.code).toBe('view.write.unknown-pending');
  });

  it('lets a new intent through once the unknown one is abandoned', async () => {
    const { ledger, store } = harness();
    const runtime = fakeRuntime();
    store.save.mockRejectedValueOnce(new ViewStoreError('UNAVAILABLE', 'down'));

    const failure = await expectWriteError(
      ledger.dispatch(savePayload(), runtime),
    );
    ledger.abandonWrite(failure.handle);
    expect(ledger.pendingWrites().size).toBe(0);
    expect(runtime.setWrite).toHaveBeenLastCalledWith(null);

    await ledger.dispatch(savePayload(), runtime);
    expect(requestIds(store.save)).toEqual(['request-1', 'request-2']);
  });

  it('does not hold a rejection against a new intent', async () => {
    const { ledger, store } = harness();
    const runtime = fakeRuntime();
    store.save.mockRejectedValueOnce(new ViewStoreError('FORBIDDEN', 'no'));

    const failure = await expectWriteError(
      ledger.dispatch(savePayload(), runtime),
    );
    expect(failure.state).toMatchObject({ kind: 'rejected' });
    await ledger.dispatch(savePayload(), runtime);
    expect(store.save).toHaveBeenCalledTimes(2);
  });
});

describe('WriteLedger conflicts', () => {
  it('reloads the server state, replacing the draft of a save', async () => {
    const { ledger, store, noted } = harness();
    const runtime = fakeRuntime();
    const remote = { ...mine, revision: '9', title: 'Theirs' };
    store.save.mockRejectedValueOnce(
      new ViewStoreError('CONFLICT', 'moved', { instance: remote }),
    );

    const failure = await expectWriteError(
      ledger.dispatch(savePayload(), runtime),
    );
    expect(failure.state).toMatchObject({ kind: 'conflict', remote });

    const reloaded = await ledger.resolveConflict(failure.handle, 'reload');
    expect(reloaded).toEqual(remote);
    expect(runtime.adoptSaved).toHaveBeenCalledWith(remote);
    expect(runtime.moveBaseline).not.toHaveBeenCalled();
    expect(noted).toEqual([remote]);
    expect(ledger.pendingWrites().size).toBe(0);
    // Reloading takes what the server holds; it sends nothing of its own.
    expect(store.save).toHaveBeenCalledTimes(1);
  });

  it('overwrites by replaying the intent at the reported revision', async () => {
    const { ledger, store } = harness();
    const runtime = fakeRuntime();
    const remote = { ...mine, revision: '9', title: 'Theirs' };
    store.save.mockRejectedValueOnce(
      new ViewStoreError('CONFLICT', 'moved', { instance: remote }),
    );

    const failure = await expectWriteError(
      ledger.dispatch(savePayload(), runtime),
    );
    await ledger.resolveConflict(failure.handle, 'overwrite');

    expect(store.save.mock.calls[1][2]).toBe('9');
    // A new expectation is a new logical write, so it takes a new key.
    expect(requestIds(store.save)).toEqual(['request-1', 'request-2']);
    expect(ledger.pendingWrites().size).toBe(0);
  });

  it('asks the store for a remote state the conflict did not carry', async () => {
    const { ledger, store } = harness();
    store.save.mockRejectedValueOnce(new ViewStoreError('CONFLICT', 'moved'));

    const failure = await expectWriteError(
      ledger.dispatch(savePayload(), undefined),
    );
    expect(store.get).toHaveBeenCalledWith(mine.id);
    expect(failure.state).toMatchObject({
      kind: 'conflict',
      remote: { revision: '9' },
    });
  });

  /**
   * The two members are read by what was written, not by what happens to be
   * on the error. A store that filled in the wrong one is not believed: the
   * ledger reads nothing from it and goes to the store for the real state,
   * where the one member used to be taken at face value and a preference
   * conflict could reach the screen carrying an instance.
   */
  it('ignores a conflict state the write was not about', async () => {
    const { ledger, store } = harness();
    store.setPreferences.mockRejectedValueOnce(
      new ViewStoreError('CONFLICT', 'moved', { instance: mine }),
    );

    const failure = await expectWriteError(
      ledger.dispatch(
        { action: 'preferences', definitionId: 'orders', next: preferences },
        undefined,
      ),
    );

    expect(store.getPreferences).toHaveBeenCalledWith('orders');
    expect(failure.state).toMatchObject({
      kind: 'conflict',
      remote: preferences,
    });
  });

  it('reports a conflict it cannot read back as a rejection', async () => {
    const { ledger, store } = harness();
    store.save.mockRejectedValueOnce(new ViewStoreError('CONFLICT', 'moved'));
    store.get.mockRejectedValueOnce(new Error('offline'));

    const failure = await expectWriteError(
      ledger.dispatch(savePayload(), undefined),
    );
    expect(failure.state).toMatchObject({
      kind: 'rejected',
      issue: { code: 'view.write.conflict-unreadable' },
    });
  });

  it('reloads a preferences conflict through the host', async () => {
    const { ledger, store, reloaded } = harness();
    store.setPreferences.mockRejectedValueOnce(
      new ViewStoreError('CONFLICT', 'moved', {
        preferences: { ...preferences, revision: '9' },
      }),
    );

    const failure = await expectWriteError(
      ledger.dispatch(
        { action: 'preferences', definitionId: 'orders', next: preferences },
        undefined,
      ),
    );
    const result = await ledger.resolveConflict(failure.handle, 'reload');
    expect(result).toEqual(preferences);
    expect(reloaded).toEqual(['orders']);
  });

  it('refuses to resolve an outcome that is not a conflict', async () => {
    const { ledger, store } = harness();
    store.save.mockRejectedValueOnce(new ViewStoreError('UNAVAILABLE', 'down'));

    const failure = await expectWriteError(
      ledger.dispatch(savePayload(), undefined),
    );
    const error = await expectCommandError(
      ledger.resolveConflict(failure.handle, 'reload'),
    );
    expect(error.issue.code).toBe('view.write.not-a-conflict');
  });

  it('refreshes the summary on a delete conflict', async () => {
    const { ledger, store, noted } = harness();
    const remote = { ...mine, revision: '9', title: 'Theirs' };
    store.delete.mockRejectedValueOnce(
      new ViewStoreError('CONFLICT', 'moved', { instance: remote }),
    );

    await expectWriteError(
      ledger.dispatch(
        {
          action: 'delete',
          id: mine.id,
          definitionId: 'orders',
          revision: '1',
        },
        undefined,
      ),
    );
    expect(noted).toEqual([remote]);
  });
});

describe('WriteLedger effects', () => {
  it('advances the owner and every other holder of the instance', async () => {
    const other = fakeRuntime('runtime-2');
    const { ledger, noted } = harness([other]);
    const runtime = fakeRuntime();

    await ledger.dispatch(savePayload(), runtime);
    const saved = { ...mine, revision: '2' };
    expect(runtime.markSaved).toHaveBeenCalledWith(saved);
    expect(other.moveBaseline).toHaveBeenCalledWith(saved);
    expect(noted).toEqual([saved]);
  });

  it('drops a deleted instance and remembers confirmed preferences', async () => {
    const { ledger, dropped, notedPreferences, changes } = harness();
    await ledger.dispatch(
      { action: 'delete', id: mine.id, definitionId: 'orders', revision: '1' },
      undefined,
    );
    expect(dropped).toEqual([mine.id]);
    // Nothing else in a delete names the list it changed, which is why the
    // definition travels in the body.
    expect(changes).toEqual([
      { definitionId: 'orders', kind: 'delete', id: mine.id },
    ]);

    await ledger.dispatch(
      { action: 'preferences', definitionId: 'orders', next: preferences },
      undefined,
    );
    expect(notedPreferences).toEqual([preferences]);
  });

  it('binds a first save to its runtime and leaves a copy alone', async () => {
    const { ledger } = harness();
    const runtime = fakeRuntime();
    const store = {
      create: vi.fn(async () => mine),
    } as unknown as ViewStore;
    const bound = new WriteLedger({
      store,
      newId: () => 'request-1',
      noteInstance: () => {},
      dropInstance: () => {},
      notePreferences: () => {},
      readPreferences: async () => preferences,
      holders: () => [],
      noteChange: () => {},
    });
    const input = {
      definitionId: 'orders',
      title: 'Mine',
      scope: 'personal' as const,
      config: recordConfig(),
    };

    await bound.dispatch(
      { action: 'create', input, intent: 'save-as' },
      runtime,
    );
    expect(runtime.markSaved).not.toHaveBeenCalled();

    await bound.dispatch(
      { action: 'create', input, intent: 'first-save' },
      runtime,
    );
    expect(runtime.markSaved).toHaveBeenCalledWith(mine);
    expect(ledger.pendingWrites().size).toBe(0);
  });
});

describe('WriteLedger with a disposed owner', () => {
  it('keeps the outcome when the owner goes before the answer arrives', async () => {
    const { ledger, store } = harness();
    const runtime = fakeRuntime();
    store.save.mockImplementationOnce(async () => {
      // The user closed the view while the request was out.
      runtime.dispose();
      throw new ViewStoreError('UNAVAILABLE', 'down');
    });

    const failure = await expectWriteError(
      ledger.dispatch(savePayload(), runtime),
    );
    // The runtime refuses the outcome, but the ledger still holds it, so the
    // handle stays the way to retry or abandon what may have landed.
    expect(runtime.getSnapshot().write).toBeNull();
    expect(ledger.pendingWrites().get(failure.handle.id)).toBe(failure.state);

    await ledger.retryWrite(failure.handle);
    expect(requestIds(store.save)).toEqual(['request-1', 'request-1']);
    expect(runtime.markSaved).toHaveBeenCalled();
    expect(ledger.pendingWrites().size).toBe(0);
  });

  it('settles a write only while the owner still reports it', async () => {
    const { ledger, store } = harness();
    const runtime = fakeRuntime();
    store.save.mockRejectedValueOnce(new ViewStoreError('UNAVAILABLE', 'down'));

    const failure = await expectWriteError(
      ledger.dispatch(savePayload(), runtime),
    );
    // The runtime has moved on to a later write of its own; settling the one
    // behind it must not take that later outcome off the screen.
    runtime.setWrite({ requestId: 'request-9' } as never);
    ledger.abandonWrite(failure.handle);
    expect(runtime.getSnapshot().write).toEqual({ requestId: 'request-9' });
  });
});
