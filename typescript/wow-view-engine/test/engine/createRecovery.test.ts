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

import { afterEach, expect, it, vi } from 'vitest';
import { ViewEngine } from '../../src/record/ViewEngine.js';
import { MemoryViewHost } from '../../src/record/MemoryViewHost.js';
import {
  ViewServiceError,
  type ViewCreateContext,
} from '../../src/record/viewServiceContract.js';
import type { ViewInstance } from '../../src/record/recordModel.js';
import type { ViewHost } from '../../src/record/ViewHost.js';
import { definition, instance, deferred } from './fixtures.js';
const engines: ViewEngine[] = [];
afterEach(() => {
  for (const engine of engines.splice(0)) engine.dispose();
});
function service(defaultInstanceId: string | null = 'mine') {
  const values = new Map<string, string>();
  return new MemoryViewHost({
    serviceKey: 'recover',
    scopeKey: 'alice',
    definition,
    instances: { instances: [instance()], defaultInstanceId },
    store: values,
    resolveSource: () => ({ paged: async () => ({ list: [], total: 0 }) }),
  });
}
function engineFor(host: ViewHost) {
  const engine = new ViewEngine({ definitionId: definition.id, host });
  engines.push(engine);
  return engine;
}
const copyOptions = {
  title: 'Shared copy',
  scope: { type: 'public', source: 'shared' } as const,
};
it('confirms an unknown creation by the same request, never by another identical instance', async () => {
  const host = service();
  let submitted!: Omit<ViewInstance, 'id' | 'revision'>,
    context!: ViewCreateContext;
  const create = vi.fn(
    async (input: typeof submitted, ctx: ViewCreateContext) => {
      if (create.mock.calls.length === 1) {
        submitted = input;
        context = ctx;
        throw new ViewServiceError('UNKNOWN_OUTCOME', 'response missing');
      }
      return host.instance.create(input, ctx);
    },
  );
  const engine = engineFor({
    ...host,
    instance: { ...host.instance, create },
    resolveSource: id => host.resolveSource(id),
  });
  await engine.load();
  await expect(engine.saveAs(copyOptions)).rejects.toThrow('response missing');
  const other = await host.instance.create(submitted, {
    requestId: 'another-request',
  });
  await engine.reloadInstance();
  const own = await host.instance.create(submitted, context);
  expect(engine.getSnapshot().selectedInstanceId).toBe(own.id);
  expect(own.id).not.toBe(other.id);
  expect(create.mock.calls[1][1].requestId).toBe(context.requestId);
  expect(engine.getSnapshot().sessions.mine.requiresReload).toBe(false);
});
it('retains the original idempotency key after a denied retry of an uncertain creation', async () => {
  const host = service();
  const create = vi.fn(
    async (
      input: Omit<ViewInstance, 'id' | 'revision'>,
      ctx: ViewCreateContext,
    ) => {
      const call = create.mock.calls.length;
      if (call === 2) throw new ViewServiceError('FORBIDDEN', 'revoked');
      const created = await host.instance.create(input, ctx);
      if (call === 1)
        throw new ViewServiceError('UNKNOWN_OUTCOME', 'response missing');
      return created;
    },
  );
  const engine = engineFor({
    ...host,
    instance: { ...host.instance, create },
    resolveSource: id => host.resolveSource(id),
  });
  await engine.load();
  await expect(engine.saveAs(copyOptions)).rejects.toThrow('response missing');
  await expect(engine.saveAs(copyOptions)).rejects.toThrow('revoked');
  await engine.saveAs(copyOptions);
  expect(new Set(create.mock.calls.map(([, ctx]) => ctx.requestId)).size).toBe(
    1,
  );
  expect(
    (await host.instance.list(definition.id)).instances.filter(
      item => item.title === copyOptions.title,
    ),
  ).toHaveLength(1);
});
it.each([false, true])(
  'keeps original create retry usable after full load (committed=%s)',
  async committed => {
    const host = service();
    const create = vi.fn(
      async (
        input: Omit<ViewInstance, 'id' | 'revision'>,
        ctx: ViewCreateContext,
      ) => {
        if (create.mock.calls.length === 1) {
          if (committed) await host.instance.create(input, ctx);
          throw new ViewServiceError('UNAVAILABLE', 'try again');
        }
        return host.instance.create(input, ctx);
      },
    );
    const engine = engineFor({
      ...host,
      instance: { ...host.instance, create },
      resolveSource: id => host.resolveSource(id),
    });
    await engine.load();
    await expect(engine.saveAs(copyOptions)).rejects.toThrow();
    await engine.load();
    await engine.saveAs(copyOptions);
    expect(create.mock.calls[0][1].requestId).toBe(
      create.mock.calls[1][1].requestId,
    );
    expect(new Set(engine.getSnapshot().instanceIds).size).toBe(
      engine.getSnapshot().instanceIds.length,
    );
    expect(
      (await host.instance.list(definition.id)).instances.filter(
        item => item.title === copyOptions.title,
      ),
    ).toHaveLength(1);
    expect(engine.getSnapshot().sessions.mine.requiresReload).toBe(false);
  },
);
it('replays an invalid create response with its original key and preserves newer source edits', async () => {
  const host = service();
  const create = vi.fn(
    async (
      input: Omit<ViewInstance, 'id' | 'revision'>,
      ctx: ViewCreateContext,
    ) => {
      const created = await host.instance.create(input, ctx);
      return create.mock.calls.length === 1
        ? (null as unknown as ViewInstance)
        : created;
    },
  );
  const engine = engineFor({
    ...host,
    instance: { ...host.instance, create },
    resolveSource: id => host.resolveSource(id),
  });
  await engine.load();
  await expect(engine.saveAs(copyOptions)).rejects.toThrow();
  engine.setColumns([
    { id: 'amount', kind: 'field', field: 'state.amount', width: 321 },
  ]);
  await engine.reloadInstance();
  expect(create.mock.calls[0][1].requestId).toBe(
    create.mock.calls[1][1].requestId,
  );
  const state = engine.getSnapshot(),
    created = state.sessions[state.selectedInstanceId!];
  expect(created.instance.config.presentation.table.columns[0].width).toBe(321);
  expect(created.dirty).toBe(true);
  expect((await host.instance.list(definition.id)).instances).toHaveLength(2);
});
it('finishes a lost deletion response by idempotent retry', async () => {
  const host = service();
  let first = true;
  const engine = engineFor({
    ...host,
    instance: {
      ...host.instance,
      delete: async (id, revision) => {
        const result = await host.instance.delete(id, revision);
        if (first) {
          first = false;
          throw new ViewServiceError(
            'UNKNOWN_OUTCOME',
            'deleted response missing',
          );
        }
        return result;
      },
    },
    resolveSource: id => host.resolveSource(id),
  });
  await engine.load();
  await expect(engine.deleteInstance()).rejects.toThrow(
    'deleted response missing',
  );
  await engine.deleteInstance();
  expect(engine.getSnapshot().instanceIds).toEqual([]);
  expect(engine.getSnapshot().selectedInstanceId).toBeNull();
});
it('preserves explicit no-default preference across creation and host-backed loading', async () => {
  const host = service(null),
    engine = engineFor(host);
  await engine.load();
  expect(engine.getSnapshot().selectedInstanceId).toBeNull();
  const { definitionId, kind, scope, config } = instance();
  await host.instance.create(
    { definitionId, kind, scope, config, title: 'new' },
    { requestId: 'new' },
  );
  expect(
    (await host.instance.list(definition.id)).defaultInstanceId,
  ).toBeNull();
});

it('retains creation identity when a full load overlaps the original response', async () => {
  const host = service();
  const response = deferred<ViewInstance>();
  let persisted: ViewInstance | undefined;
  const create = vi.fn(
    async (
      input: Omit<ViewInstance, 'id' | 'revision'>,
      ctx: ViewCreateContext,
    ) => {
      const result = await host.instance.create(input, ctx);
      if (create.mock.calls.length === 1) {
        persisted = result;
        return response.promise;
      }
      return result;
    },
  );
  const engine = engineFor({
    ...host,
    instance: { ...host.instance, create },
    resolveSource: id => host.resolveSource(id),
  });
  await engine.load();
  const saving = engine.saveAs(copyOptions);
  await vi.waitFor(() => expect(persisted).toBeDefined());
  await engine.load();
  response.resolve(persisted!);
  await saving;
  await engine.reloadInstance();
  expect(engine.getSnapshot().selectedInstanceId).toBe(persisted!.id);
  expect(create.mock.calls[0][1].requestId).toBe(
    create.mock.calls[1][1].requestId,
  );
  expect(engine.getSnapshot().sessions.mine.requiresReload).toBe(false);
});

it.each(['loading', 'loaded'] as const)(
  'clears a definitively rejected original create after the source is %s again',
  async timing => {
    const host = service();
    const response = deferred<ViewInstance>();
    const listing = deferred<Awaited<ReturnType<typeof host.instance.list>>>();
    let blockListing = false;
    const create = vi.fn(
      (
        input: Omit<ViewInstance, 'id' | 'revision'>,
        context: ViewCreateContext,
      ) =>
        create.mock.calls.length === 1
          ? response.promise
          : host.instance.create(input, context),
    );
    const engine = engineFor({
      ...host,
      instance: {
        ...host.instance,
        create,
        list: (id, signal) =>
          blockListing ? listing.promise : host.instance.list(id, signal),
      },
      resolveSource: id => host.resolveSource(id),
    });
    await engine.load();
    const saving = engine.saveAs(copyOptions);
    await vi.waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    blockListing = timing === 'loading';
    const reloading = engine.load();
    if (timing === 'loaded') {
      await reloading;
      expect(engine.getSnapshot().sessions.mine.requiresReload).toBe(true);
    }
    response.reject(new ViewServiceError('FORBIDDEN', 'create denied'));
    await saving;
    blockListing = false;
    listing.resolve(await host.instance.list(definition.id));
    await reloading;
    expect(engine.getSnapshot().sessions.mine.requiresReload).toBe(false);
    expect(engine.getSnapshot().sessions.mine.writeError).toBeNull();
    await engine.saveAs({ ...copyOptions, title: 'Allowed copy' });
    expect(create).toHaveBeenCalledTimes(2);
    expect(create.mock.calls[1][1].requestId).not.toBe(
      create.mock.calls[0][1].requestId,
    );
    expect((await host.instance.list(definition.id)).instances).toHaveLength(2);
  },
);

it('preserves edits to an existing copy made by synchronous query-cancellation observers', async () => {
  const host = service();
  const pending = deferred<{ list: never[]; total: number }>(),
    started = deferred<void>();
  let block = false;
  const create = vi.fn(
    async (
      input: Omit<ViewInstance, 'id' | 'revision'>,
      ctx: ViewCreateContext,
    ) => {
      const result = await host.instance.create(input, ctx);
      if (create.mock.calls.length === 1)
        throw new ViewServiceError('UNKNOWN_OUTCOME', 'lost');
      return result;
    },
  );
  const engine = engineFor({
    ...host,
    instance: { ...host.instance, create },
    resolveSource: () => ({
      paged: async () => {
        if (block) {
          started.resolve();
          return pending.promise;
        }
        return { list: [], total: 0 };
      },
    }),
  });
  await engine.load();
  await expect(engine.saveAs(copyOptions)).rejects.toThrow('lost');
  await engine.load();
  const copyId = engine.getSnapshot().instanceIds.find(id => id !== 'mine')!;
  block = true;
  const reading = engine.refresh();
  await started.promise;
  let edited = false;
  const stop = engine.subscribe(() => {
    const source = engine.getSnapshot().sessions.mine;
    if (
      !edited &&
      source.queryStatus === 'idle' &&
      source.writeStatus === 'creating'
    ) {
      edited = true;
      block = false;
      engine.setTitle('Newer independent edit', copyId);
    }
  });
  try {
    await engine.saveAs(copyOptions);
    expect(edited).toBe(true);
    expect(engine.getSnapshot().sessions[copyId].instance.title).toBe(
      'Newer independent edit',
    );
  } finally {
    stop();
    pending.resolve({ list: [], total: 0 });
    await reading;
  }
});
