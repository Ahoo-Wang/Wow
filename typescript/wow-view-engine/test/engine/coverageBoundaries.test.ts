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
import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import {
  newFilterNode,
  createFilterConfiguration,
} from '../../src/filter/filterCore.js';

import { afterEach, expect, it, vi } from 'vitest';
import {} from '@ahoo-wang/fetcher-wow';

import type { FilterMode } from '../../src/filter/filterModel.js';
import type { ViewEngine } from '../../src/engine/ViewEngine.js';
import type { ViewHost } from '../../src/contracts/ViewHost.js';
import type {
  ViewEngineOptions,
  ViewInstance,
  ViewInstanceList,
} from '../../src/contracts/viewModel.js';
import { ViewServiceError } from '../../src/record/viewServiceContract.js';
import {
  deferred,
  definition,
  instance,
  managementPermissions,
  selected,
  setup,
} from './fixtures.js';

const engines: ViewEngine[] = [];
function fixture(options: Partial<ViewEngineOptions> = {}) {
  const result = setup(options);
  engines.push(result.engine);
  return result;
}
afterEach(() => engines.splice(0).forEach(engine => engine.dispose()));

it.each(['definition', 'instances'] as const)(
  'recovers from a missing %s provider after host configuration is repaired',
  async missing => {
    const { engine, host, paged } = fixture({ [missing]: undefined });
    await expect(engine.load()).rejects.toThrow(
      missing === 'definition' ? 'definition.load' : 'instance.list',
    );
    expect(engine.getSnapshot().status).toBe('error');
    expect(paged).not.toHaveBeenCalled();
    engine.updateHost({
      ...host,
      definition: { load: async () => definition },
      instance: {
        ...host.instance,
        list: async () => ({
          instances: [instance()],
          defaultInstanceId: 'mine',
        }),
      },
    });
    await engine.load();
    expect(engine.getSnapshot()).toMatchObject({
      status: 'ready',
      selectedInstanceId: 'mine',
    });
    expect(paged).toHaveBeenCalledOnce();
  },
);

it('rejects a definition returned for another resource and permits a corrected load', async () => {
  const load = vi
    .fn()
    .mockResolvedValueOnce({ ...definition, id: 'other' })
    .mockResolvedValue(definition);
  const { engine, paged } = fixture({
    definition: undefined,
    host: { definition: { load } } as ViewHost,
  });
  await expect(engine.load()).rejects.toThrow('定义 ID 不匹配');
  expect(paged).not.toHaveBeenCalled();
  await engine.load();
  expect(engine.getSnapshot().definition?.id).toBe('orders');
  expect(paged).toHaveBeenCalledOnce();
});

it('retains the workspace during failed remote selection and allows retry', async () => {
  const load = vi
    .fn()
    .mockRejectedValueOnce(new Error('instance offline'))
    .mockResolvedValue(instance('remote'));
  const { engine, paged } = fixture({
    host: { instance: { load } } as ViewHost,
  });
  await engine.load();
  engine.setTitle('Keep this draft');
  const before = selected(engine);
  await expect(engine.selectInstance('remote')).rejects.toThrow(
    'instance offline',
  );
  expect(engine.getSnapshot().error).toBe('instance offline');
  expect(engine.getSnapshot().selectedInstanceId).toBe('mine');
  expect(selected(engine, 'mine')).toBe(before);
  expect(engine.getSnapshot().instanceIds).not.toContain('remote');
  expect(paged).toHaveBeenCalledOnce();
  await engine.selectInstance('remote');
  expect(engine.getSnapshot()).toMatchObject({
    selectedInstanceId: 'remote',
    error: null,
  });
  expect(selected(engine, 'mine').instance.title).toBe('Keep this draft');
  expect(paged).toHaveBeenCalledTimes(2);
});

it('cancels a pending summary when navigating and ignores its late result', async () => {
  const summary = deferred<{ summary0: number }[]>();
  const mine = instance();
  mine.config.presentation.table.columns[0].summary = ['SUM'];
  const aggregate = vi.fn(
    (_query, _attributes, controller: AbortController) => {
      expect(controller.signal.aborted).toBe(false);
      return summary.promise;
    },
  );
  const { engine } = fixture({
    instances: {
      instances: [mine, instance('shared')],
      defaultInstanceId: 'mine',
    },
    host: {
      resolveSource: () => ({
        paged: async () => ({ list: [], total: 0 }),
        aggregate,
      }),
    },
  });
  await engine.load();
  expect(selected(engine).allSummary.status).toBe('loading');
  await engine.selectInstance('shared');
  expect(aggregate.mock.calls[0][2].signal.aborted).toBe(true);
  expect(selected(engine, 'mine').allSummary.status).toBe('idle');
  summary.resolve([{ summary0: 100 }]);
  await Promise.resolve();
  expect(selected(engine, 'mine').allSummary.status).toBe('idle');
  expect(engine.getSnapshot().selectedInstanceId).toBe('shared');
});

it('rejects invalid editing commands without changing the draft or dispatching records', async () => {
  const { engine, paged } = fixture();
  paged.mockResolvedValue({
    total: 100,
    list: [{ state: { id: 'a', amount: 10 } }],
  });
  await engine.load();
  const before = engine.getSnapshot();
  expect(() =>
    engine
      .record(engine.getSnapshot().selectedInstanceId!)
      .setFilterDraft(
        createFilterConfiguration(selected(engine).filterDraft.root),
        'true' as unknown as boolean,
      ),
  ).toThrow('有效性必须是布尔值');
  expect(() =>
    engine
      .record(engine.getSnapshot().selectedInstanceId!)
      .setFilterMode('unknown' as FilterMode),
  ).toThrow('筛选模式无效');
  expect(() =>
    engine
      .record(engine.getSnapshot().selectedInstanceId!)
      .setSelection(['not-on-page']),
  ).toThrow('当前查询结果');
  await expect(
    engine.record(engine.getSnapshot().selectedInstanceId!).setPage(0),
  ).rejects.toThrow('页码必须是正整数');
  expect(engine.getSnapshot()).toBe(before);
  expect(paged).toHaveBeenCalledOnce();
  engine.record(engine.getSnapshot().selectedInstanceId!).setFilterDraft(
    createFilterConfiguration({
      ...newFilterNode(FilterOperator.OR),
      operands: [
        {
          ...newFilterNode(FilterOperator.EQ, 'state.amount'),
          props: { value: 1 },
        },
        {
          ...newFilterNode(FilterOperator.EQ, 'state.amount'),
          props: { value: 2 },
        },
      ],
    }),
  );
  expect(() =>
    engine
      .record(engine.getSnapshot().selectedInstanceId!)
      .setFilterMode('simple'),
  ).toThrow('高级筛选模式');
  expect(paged).toHaveBeenCalledOnce();
  await engine.restore();
  await engine.record(engine.getSnapshot().selectedInstanceId!).nextPage();
  expect(selected(engine).page).toBe(2);
  expect(paged).toHaveBeenCalledTimes(3);
});

it('makes normalized rename and unchanged ordering no-ops while rejecting empty names', async () => {
  const rename = vi.fn(),
    saveOrder = vi.fn();
  const { engine } = fixture({
    host: {
      instance: { rename },
      preference: { saveOrder },
      permission: { getInstance: managementPermissions },
    } as ViewHost,
  });
  await engine.load();
  const before = engine.getSnapshot();
  await expect(engine.renameInstance('   ')).rejects.toThrow('名称不能为空');
  await engine.renameInstance(' mine ');
  await engine.reorderInstances(['mine', 'shared']);
  expect(rename).not.toHaveBeenCalled();
  expect(saveOrder).not.toHaveBeenCalled();
  expect(engine.getSnapshot()).toBe(before);
});

it('fails closed when permission providers throw and recovers when policy is available', async () => {
  const saveOrder = vi.fn().mockResolvedValue(undefined);
  const { engine, host } = fixture({
    host: {
      preference: { saveOrder },
      permission: {
        getInstance: () => {
          throw new Error('policy offline');
        },
        getDefinition: () => {
          throw new Error('policy offline');
        },
      },
    } as ViewHost,
  });
  await engine.load();
  expect(engine.getPermissions()).toMatchObject({
    save: false,
    saveAsPersonal: false,
  });
  expect(engine.canReorderInstances()).toBe(false);
  await expect(engine.save()).rejects.toThrow('未允许');
  await expect(engine.reorderInstances(['shared', 'mine'])).rejects.toThrow(
    '排序接口',
  );
  expect(host.instance!.save).not.toHaveBeenCalled();
  expect(saveOrder).not.toHaveBeenCalled();
  engine.updateHost({
    ...host,
    permission: {
      getInstance: managementPermissions,
      getDefinition: () => ({ reorder: true }),
    },
  });
  await engine.save();
  await engine.reorderInstances(['shared', 'mine']);
  expect(engine.getSnapshot().instanceIds).toEqual(['shared', 'mine']);
});

it('rejects reload without a provider or during a write, then reads the completed revision', async () => {
  const pending = deferred<ViewInstance>();
  const load = vi.fn().mockResolvedValue({ ...instance(), revision: 'r2' });
  const { engine, host } = fixture();
  await engine.load();
  await expect(engine.reloadInstance()).rejects.toThrow(
    'instance.load 或 instance.list',
  );
  expect(selected(engine).baseline.revision).toBe('r1');
  engine.updateHost({
    ...host,
    instance: { ...host.instance, load, save: () => pending.promise },
  });
  const writing = engine.save();
  await expect(engine.reloadInstance()).rejects.toThrow('正在写入');
  expect(load).not.toHaveBeenCalled();
  pending.resolve({ ...instance(), revision: 'r2' });
  await writing;
  await engine.reloadInstance();
  expect(selected(engine)).toMatchObject({
    baseline: { revision: 'r2' },
    writeError: null,
  });
  expect(load).toHaveBeenCalledOnce();
});

it('discards a superseded list reload even when its host ignores cancellation', async () => {
  const old = deferred<ViewInstanceList>();
  const list = vi
    .fn()
    .mockReturnValueOnce(old.promise)
    .mockResolvedValue({
      instances: [{ ...instance(), revision: 'r3' }],
      defaultInstanceId: 'mine',
    });
  const { engine } = fixture({ host: { instance: { list } } as ViewHost });
  await engine.load();
  engine.setTitle('Local draft');
  const stale = engine.reloadInstance();
  await engine.reloadInstance();
  old.resolve({
    instances: [{ ...instance(), revision: 'r2' }],
    defaultInstanceId: 'mine',
  });
  await stale;
  expect(selected(engine)).toMatchObject({
    baseline: { revision: 'r3' },
    instance: { title: 'Local draft', revision: 'r3' },
  });
});

it.each([
  'permissions revoked',
  'changed content',
  'existing identity',
] as const)(
  'retains uncertain create identity after recovery encounters %s',
  async failure => {
    const create = vi
      .fn()
      .mockRejectedValueOnce(
        new ViewServiceError('UNKNOWN_OUTCOME', 'lost response'),
      );
    const { engine, host } = fixture({
      host: { instance: { create } } as ViewHost,
    });
    await engine.load();
    const request = { title: 'Copy', scope: { type: 'personal' } as const };
    await expect(engine.saveAs(request)).rejects.toThrow('lost response');
    const submitted = { ...instance('created'), title: request.title };
    if (failure === 'permissions revoked') {
      host.permission!.getInstance = () => ({ saveAsPersonal: false });
      await expect(engine.reloadInstance()).rejects.toThrow('未允许重试');
      expect(create).toHaveBeenCalledOnce();
      host.permission!.getInstance = managementPermissions;
    } else {
      create.mockResolvedValueOnce(
        failure === 'changed content'
          ? { ...submitted, title: 'Normalized title' }
          : { ...submitted, id: 'mine' },
      );
      await expect(engine.reloadInstance()).rejects.toThrow(
        failure === 'changed content' ? '原样保存契约' : '没有新的实例 ID',
      );
    }
    expect(selected(engine)).toMatchObject({
      requiresReload: true,
      baseline: { id: 'mine' },
    });
    create.mockResolvedValueOnce(submitted);
    await engine.reloadInstance();
    expect(engine.getSnapshot().selectedInstanceId).toBe('created');
    expect(selected(engine).instance.title).toBe('Copy');
    expect(selected(engine, 'mine').requiresReload).toBe(false);
    expect(new Set(create.mock.calls.map(call => call[1].requestId)).size).toBe(
      1,
    );
  },
);

it('ignores a replayed create response after the engine has been disposed', async () => {
  const replay = deferred<ViewInstance>();
  const create = vi
    .fn()
    .mockRejectedValueOnce(
      new ViewServiceError('UNKNOWN_OUTCOME', 'lost response'),
    )
    .mockReturnValueOnce(replay.promise);
  const { engine } = fixture({ host: { instance: { create } } as ViewHost });
  await engine.load();
  await expect(
    engine.saveAs({ title: 'Copy', scope: { type: 'personal' } }),
  ).rejects.toThrow('lost response');
  const reloading = engine.reloadInstance();
  engine.dispose();
  const disposed = engine.getSnapshot();
  replay.resolve({ ...instance('created'), title: 'Copy' });
  await reloading;
  expect(engine.getSnapshot()).toBe(disposed);
  expect(engine.getSnapshot().instanceIds).not.toContain('created');
});

it('keeps a malformed query response out of the session and allows explicit retry', async () => {
  const { engine, paged } = fixture();
  await engine.load();
  const previousRows = selected(engine).rows;
  paged.mockResolvedValueOnce([]);
  await expect(
    engine.record(engine.getSnapshot().selectedInstanceId!).refresh(),
  ).rejects.toThrow('分页对象');
  expect(selected(engine).queryStatus).toBe('error');
  expect(selected(engine).rows).toEqual(previousRows);
  await engine.record(engine.getSnapshot().selectedInstanceId!).refresh();
  expect(selected(engine).queryStatus).toBe('success');
});

it('reports overflowing page summaries while retaining valid records and recovers on a fresh result', async () => {
  const saved = instance();
  saved.config.presentation.table.columns[0].summary = ['SUM'];
  const { engine, paged } = fixture({
    instances: { instances: [saved], defaultInstanceId: 'mine' },
  });
  paged.mockResolvedValueOnce({
    list: [
      { state: { id: 'a', amount: Number.MAX_VALUE } },
      { state: { id: 'b', amount: Number.MAX_VALUE } },
    ],
    total: 2,
  });
  await engine.load();
  expect(selected(engine)).toMatchObject({
    queryStatus: 'success',
    pageSummary: { status: 'error' },
  });
  expect(selected(engine).pageSummary.error).toContain('超出数值范围');
  expect(selected(engine).rows).toHaveLength(2);
  await engine.record(engine.getSnapshot().selectedInstanceId!).refresh();
  expect(selected(engine).pageSummary.status).toBe('success');
});

it('does not dispatch a reload when canceling the active query disposes its engine', async () => {
  const response = deferred<{ list: never[]; total: number }>();
  const load = vi.fn();
  const { engine, paged } = fixture({
    host: { instance: { load } } as ViewHost,
  });
  await engine.load();
  paged.mockImplementationOnce(
    (_query, _attributes, controller: AbortController) => {
      controller.signal.addEventListener('abort', () => engine.dispose(), {
        once: true,
      });
      return response.promise;
    },
  );
  const reading = engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .refresh();
  await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
  await engine.reloadInstance();
  expect(load).not.toHaveBeenCalled();
  const disposed = engine.getSnapshot();
  response.resolve({ list: [], total: 0 });
  await reading;
  expect(engine.getSnapshot()).toBe(disposed);
});

it('rejects selection before initialization and permits it after load', async () => {
  const { engine, paged } = fixture();
  await expect(
    engine.record(engine.getSnapshot().selectedInstanceId!).refresh(),
  ).rejects.toThrow('请先选择有效的视图实例');
  await expect(engine.selectInstance('mine')).rejects.toThrow(
    '视图定义尚未加载',
  );
  expect(paged).not.toHaveBeenCalled();
  await engine.load();
  await engine.selectInstance('shared');
  expect(engine.getSnapshot().selectedInstanceId).toBe('shared');
});

it('preserves navigation started by an aborted selection over the request causing that abort', async () => {
  const delayed = deferred<ViewInstance>();
  let newest: Promise<void> | undefined;
  const load = vi.fn((id: string, signal?: AbortSignal) => {
    signal?.throwIfAborted();
    if (id === 'remote') {
      signal!.addEventListener(
        'abort',
        () => {
          newest = engine.selectInstance('shared');
        },
        { once: true },
      );
      return delayed.promise;
    }
    return Promise.resolve(instance(id));
  });
  const { engine } = fixture({ host: { instance: { load } } as ViewHost });
  await engine.load();
  const previous = engine.selectInstance('remote');
  await engine.selectInstance('third');
  await newest;
  delayed.resolve(instance('remote'));
  await previous;
  expect(engine.getSnapshot().selectedInstanceId).toBe('shared');
  expect(engine.getSnapshot().instanceIds).toEqual(['mine', 'shared']);
  expect(load).toHaveBeenCalledOnce();
});
