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
/// <reference types="vite/client" />
import '@ahoo-wang/fetcher-view-engine/styles.css';
import { useRef, useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, userEvent, waitFor, within } from 'storybook/test';
import {
  AggregationFunction,
  AggregationGroupType,
  FilterOperator,
  type AggregationQuery,
} from '@ahoo-wang/fetcher-wow';
import {
  MemoryViewHost,
  compileAnalysis,
  createFilterConfiguration,
  type AnalysisRow,
  type AnalysisViewConfig,
  type RecordData,
  type ViewDefinition,
} from '@ahoo-wang/fetcher-view-engine';
import {
  Button,
  useViewEngine,
  ViewPage,
} from '@ahoo-wang/fetcher-view-engine/react';

const fieldCount = 100;
const filterCount = 20;
const warmupCount = 5;
const sampleCount = 30;
const definition: ViewDefinition = {
  id: 'analysis-performance',
  title: '分析性能验收',
  sourceId: 'performance-fixture',
  fields: [
    { field: 'group', label: '分组', type: 'string' },
    ...Array.from({ length: 99 }, (_, i) => ({
      field: `f${i + 1}`,
      label: `数值字段${i + 1}`,
      type: 'number' as const,
    })),
  ],
  analysis: {
    count: true,
    limits: { maxMetrics: 20 },
    fields: [
      { field: 'group', groups: [AggregationGroupType.TERMS], functions: [] },
      ...Array.from({ length: 99 }, (_, i) => ({
        field: `f${i + 1}`,
        groups: [],
        functions: [AggregationFunction.SUM],
        unit: 'unit',
      })),
    ],
  },
};
function configuration(
  id: string,
  rowCount: number,
  metricCount: number,
): AnalysisViewConfig {
  return {
    filters: createFilterConfiguration({
      id: 'and',
      component: { name: 'builtin' },
      operator: FilterOperator.AND,
      props: {},
      operands: Array.from({ length: filterCount }, (_, i) => ({
        id: `filter-${i}`,
        component: { name: 'builtin' },
        field: `f${i + 1}`,
        operator: FilterOperator.GTE,
        props: { value: 0 },
      })),
    }),
    dimensions: [
      {
        id: 'group',
        component: { name: 'terms' },
        field: 'group',
        alias: `bucket_${id}`,
        title: '分组名称',
        props: {},
      },
    ],
    metrics: Array.from({ length: metricCount }, (_, i) => ({
      id: `metric-${i}`,
      component: { name: 'numeric' },
      field: `f${i + 1}`,
      alias: `m${i + 1}`,
      title: `合计${i + 1}`,
      props: { function: AggregationFunction.SUM },
    })),
    sort: [],
    limit: rowCount,
    presentation: { layout: 'table', columns: [] },
  };
}
/** Exact, bounded aggregate fixtures; never an alternative aggregation interpreter. */
function createPerformanceHost(rowCount: number, metricCount: number) {
  let requests = 0,
    deferNext = false,
    abortObserved = false;
  let release: undefined | (() => void);
  const configs = ['a', 'b'].map(id => ({
    id,
    config: configuration(id, rowCount, metricCount),
  }));
  const expected = new Map(
    configs.map(({ id, config }) => {
      const compiled = compileAnalysis(config, {
        fields: definition.fields,
        capability: definition.analysis!,
      });
      if (!compiled.plan)
        throw new Error(compiled.errors.map(e => e.message).join('；'));
      return [JSON.stringify(compiled.plan.query), id];
    }),
  );
  const rows = (id: string, count: number): AnalysisRow[] =>
    Array.from({ length: count }, (_, i) =>
      Object.fromEntries([
        [`bucket_${id}`, `${id.toUpperCase()}-${String(i).padStart(5, '0')}`],
        ...Array.from({ length: metricCount }, (_, metric) => [
          `m${metric + 1}`,
          (i + 1) * (metric + 1),
        ]),
      ]),
    );
  const host = new MemoryViewHost({
    serviceKey: `performance-${rowCount}`,
    scopeKey: 'demo',
    definition,
    instances: {
      defaultInstanceId: 'a',
      instances: configs.map(({ id, config }) => ({
        id,
        definitionId: definition.id,
        kind: 'analysis',
        title: `性能实例${id.toUpperCase()}`,
        revision: '1',
        scope: { type: 'personal' as const },
        config,
      })),
    },
    resolveSource: () => ({
      async aggregate<Row extends RecordData = RecordData>(
        query: AggregationQuery,
        _attributes?: Record<string, unknown>,
        controller?: AbortController,
      ): Promise<Row[]> {
        controller?.signal.throwIfAborted();
        requests++;
        const id = expected.get(JSON.stringify(query));
        if (!id) throw new Error('性能fixture仅接受声明的预设聚合查询');
        if (deferNext) {
          deferNext = false;
          controller?.signal.addEventListener(
            'abort',
            () => {
              abortObserved = true;
            },
            { once: true },
          );
          // Deliberately ignore cancellation at the transport boundary to challenge late-result admission.
          return new Promise<Row[]>(resolve => {
            release = () =>
              resolve([
                { ...rows(id, 1)[0], [`bucket_${id}`]: 'LATE-IGNORED' },
              ] as unknown as Row[]);
          });
        }
        return rows(id, rowCount) as unknown as Row[];
      },
    }),
  });
  return {
    host,
    requests: () => requests,
    defer: () => {
      deferNext = true;
    },
    pending: () => !!release,
    aborted: () => abortObserved,
    release: () => release?.(),
  };
}
const frame = () =>
  new Promise<void>(resolve => requestAnimationFrame(() => resolve()));
const paint = async () => {
  await frame();
  await frame();
};
async function until(check: () => boolean) {
  const start = performance.now();
  while (!check()) {
    if (performance.now() - start > 15000) throw new Error('等待界面状态超时');
    await frame();
  }
}
function setInput(input: HTMLInputElement, value: string) {
  Object.getOwnPropertyDescriptor(
    HTMLInputElement.prototype,
    'value',
  )!.set!.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}
function statistics(samples: number[]) {
  const sorted = [...samples].sort((a, b) => a - b);
  return {
    samplesMs: samples,
    p50Ms: sorted[Math.ceil(sorted.length * 0.5) - 1],
    p95Ms: sorted[Math.ceil(sorted.length * 0.95) - 1],
    maxMs: sorted.at(-1),
  };
}
function environment() {
  return {
    build: import.meta.env.PROD ? 'production' : 'development',
    storybookHighlight:
      (globalThis as typeof globalThis & { FEATURES?: { highlight?: boolean } })
        .FEATURES?.highlight !== false,
    userAgent: navigator.userAgent,
    viewport: { width: innerWidth, height: innerHeight, devicePixelRatio },
    hardwareConcurrency: navigator.hardwareConcurrency,
    visibility: document.visibilityState,
    timeOrigin: performance.timeOrigin,
    measuredAt: new Date().toISOString(),
  };
}

function AnalysisPerformance({ stress = false }: { stress?: boolean }) {
  const root = useRef<HTMLDivElement>(null);
  const [runtime] = useState(() =>
    createPerformanceHost(stress ? 10000 : 100, stress ? 20 : 5),
  );
  const binding = useViewEngine({
    scopeKey: 'demo',
    definitionId: definition.id,
    host: runtime.host,
  });
  const [running, setRunning] = useState(false);
  const [report, setReport] = useState<Record<string, unknown> | null>(null);
  const engine = binding.engine;
  async function run() {
    if (!engine || !root.current) return;
    setRunning(true);
    setReport(null);
    const panel = root.current;
    const state = () => engine.getSnapshot();
    const current = () => {
      const session = state().sessions[state().selectedInstanceId!];
      if (session?.kind !== 'analysis') throw new Error('未选择分析实例');
      return session;
    };
    const resultRoot = () =>
      panel.querySelector('[data-slot="analysis-view"]:not([hidden])') ?? panel;
    const editorScope = () =>
      document.querySelector<HTMLElement>(
        '[data-slot="sheet-content"][data-open]',
      ) ?? panel;
    const input = (label: string) => {
      const element = (
        label === '维度 1 名称'
          ? document.getElementById(
              editorScope()
                .querySelector('[aria-label="编辑维度 1"]')
                ?.getAttribute('aria-controls') ?? '',
            )
          : editorScope()
      )?.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`);
      if (!element || !element.getBoundingClientRect().height)
        throw new Error(`请在宽屏下打开配置面板：${label}`);
      return element;
    };
    const click = (id: string) => {
      const button = panel.querySelector<HTMLButtonElement>(
        `[data-testid="${id}"]`,
      );
      if (!button) throw new Error('缺少性能操作按钮');
      button.click();
    };
    try {
      await until(() => {
        const selected = state().sessions[state().selectedInstanceId ?? ''];
        return (
          selected?.kind === 'analysis' &&
          selected.result?.rows.length === (stress ? 10000 : 100)
        );
      });
      if (innerWidth < 1024)
        throw new Error(
          '此性能协议要求宽度至少1024px以同时呈现配置与结果；未测量窄屏性能',
        );
      const configuration = [
        ...panel.querySelectorAll<HTMLButtonElement>('button'),
      ].find(button => button.textContent?.trim() === '配置查询');
      if (configuration?.getAttribute('aria-expanded') !== 'true')
        configuration?.click();
      await paint();
      if (stress) {
        editorScope()
          .querySelector<HTMLElement>('[aria-label="编辑维度 1"]')
          ?.click();
        await paint();
        const before = current().result!;
        runtime.defer();
        void engine
          .analysis('a')
          .run()
          .catch(() => {});
        await until(runtime.pending);
        const text = '压力验收_中文_ABCDEFGHIJKLMNOPQRSTUVWXYZ_0123456789';
        const editor = input('维度 1 名称');
        for (let length = 1; length <= text.length; length++) {
          const raw = text.slice(0, length);
          setInput(editor, raw);
          await frame();
          if (
            input('维度 1 名称').value !== raw ||
            current().instance.config.dimensions[0].title !== raw
          )
            throw new Error(`输入在第${length}字符丢失`);
        }
        click('switch-b');
        await until(
          () =>
            state().selectedInstanceId === 'b' &&
            current().result?.rows.length === 10000,
        );
        if (!runtime.aborted())
          throw new Error('切换实例未向挂起aggregate发送abort');
        runtime.release();
        await paint();
        const a = state().sessions.a;
        if (
          a.kind !== 'analysis' ||
          a.result !== before ||
          a.instance.config.dimensions[0].title !== text ||
          state().selectedInstanceId !== 'b'
        )
          throw new Error('迟到结果覆盖或输入草稿丢失');
        setReport({
          kind: 'stress',
          environment: environment(),
          fields: fieldCount,
          filters: filterCount,
          returnedRows: 10000,
          columns: 21,
          renderedDataRows: resultRoot().querySelectorAll('tbody tr').length,
          input: { text, characters: text.length, preserved: true },
          abortObserved: true,
          lateResponseIgnored: true,
          requests: runtime.requests(),
          passed: true,
          scope:
            '取消由切换实例触发；故意迟到的传输回包不得覆盖旧成功结果或新选择',
        });
      } else {
        click('switch-b');
        await until(
          () =>
            state().selectedInstanceId === 'b' &&
            current().result?.rows.length === 100,
        );
        click('switch-a');
        await until(() => state().selectedInstanceId === 'a');
        await paint();
        const queryButton = [
          ...panel.querySelectorAll<HTMLButtonElement>('button'),
        ].find(button => button.textContent?.trim() === '配置查询');
        if (queryButton?.getAttribute('aria-expanded') !== 'true')
          queryButton?.click();
        await paint();
        const advanced = [...editorScope().querySelectorAll('summary')].find(
          element => element.textContent?.includes('高级设置'),
        );
        advanced?.click();
        await paint();
        const requestsBefore = runtime.requests();
        const inputPhases: {
          lookupMs: number;
          dispatchMs: number;
          remainingMs: number;
        }[] = [];
        const inputSamples: number[] = [],
          switchSamples: number[] = [],
          warmupInput: number[] = [],
          warmupSwitch: number[] = [];
        for (let index = 0; index < warmupCount + sampleCount; index++) {
          await frame();
          const value = String(100 + (index % 2));
          const start = performance.now();
          const field = input('最多结果行数');
          const beforeDispatch = performance.now();
          setInput(field, value);
          const afterDispatch = performance.now();
          await paint();
          const elapsed = performance.now() - start;
          if (index >= warmupCount)
            inputPhases.push({
              lookupMs: beforeDispatch - start,
              dispatchMs: afterDispatch - beforeDispatch,
              remainingMs: elapsed - (afterDispatch - start),
            });
          if (
            input('最多结果行数').value !== value ||
            String(current().instance.config.limit) !== value
          )
            throw new Error('本地input未提交到引擎与DOM');
          (index < warmupCount ? warmupInput : inputSamples).push(elapsed);
        }
        // Restore the initial query meaning before switching; no query is dispatched for this edit.
        setInput(input('最多结果行数'), '100');
        await paint();
        for (let index = 0; index < warmupCount + sampleCount; index++) {
          await frame();
          const target = state().selectedInstanceId === 'a' ? 'b' : 'a';
          const start = performance.now();
          click(`switch-${target}`);
          await paint();
          const elapsed = performance.now() - start;
          if (
            state().selectedInstanceId !== target ||
            !resultRoot()
              .querySelector('tbody')
              ?.textContent?.includes(`${target.toUpperCase()}-00000`)
          )
            throw new Error('切换后结果未渲染');
          (index < warmupCount ? warmupSwitch : switchSamples).push(elapsed);
        }
        const idleFrames: number[] = [];
        for (let index = 0; index < sampleCount; index++) {
          await frame();
          const start = performance.now();
          await paint();
          idleFrames.push(performance.now() - start);
        }
        if (runtime.requests() !== requestsBefore)
          throw new Error('纯本地测量期间发生额外aggregate');
        const inputResult = statistics(inputSamples),
          switchResult = statistics(switchSamples);
        // Acceptance budgets include headroom for shared CI runners.
        const passed = inputResult.p95Ms <= 400 && switchResult.p95Ms <= 450;
        setReport({
          kind: 'performance',
          environment: environment(),
          fields: fieldCount,
          filters: filterCount,
          returnedRows: 100,
          columns: 6,
          sampleCount,
          warmupCount,
          warmup: { inputMs: warmupInput, switchMs: warmupSwitch },
          input: inputResult,
          inputPhases,
          idleFrames: statistics(idleFrames),
          instanceSwitch: switchResult,
          thresholds: { inputP95Ms: 400, switchP95Ms: 450 },
          passed,
          productionAdmitted: import.meta.env.PROD && passed,
          requestsBefore,
          requestsAfter: runtime.requests(),
          method:
            '浏览器内performance.now；合成原生input/click事件分发前至两次requestAnimationFrame回调后；先对齐一帧不计时；断言DOM和引擎状态已提交。每项5热身+30正式样本，最近秩P95，不删除离群点。实例由固定fixture按钮调用公共selectInstance，两实例结果已预热；包含真实ViewPage渲染，无网络、无工具传输时延。',
        });
      }
    } catch (error) {
      setReport({
        kind: stress ? 'stress' : 'performance',
        environment: environment(),
        passed: false,
        error: error instanceof Error ? error.message : String(error),
      });
    } finally {
      setRunning(false);
    }
  }
  return (
    <div ref={root} className="fve-root">
      <section
        aria-label="分析性能验收控制"
        className="fve:flex fve:flex-wrap fve:items-center fve:gap-2 fve:p-4"
      >
        <Button disabled={!engine || running} onClick={() => void run()}>
          开始{stress ? '压力' : '性能'}验收
        </Button>
        <Button
          data-testid="switch-a"
          variant="outline"
          disabled={!engine}
          onClick={() => void engine?.selectInstance('a')}
        >
          性能切换 A
        </Button>
        <Button
          data-testid="switch-b"
          variant="outline"
          disabled={!engine}
          onClick={() => void engine?.selectInstance('b')}
        >
          性能切换 B
        </Button>
        <span role="status">
          {running
            ? '测量中，请勿切换标签页'
            : report
              ? '测量完成'
              : '等待开始'}
        </span>
      </section>
      {report && (
        <details open className="fve:p-4">
          <summary>
            验收结果 JSON（
            {import.meta.env.PROD ? '生产构建' : '开发构建，不作性能准入结论'}）
          </summary>
          <pre
            data-testid="analysis-performance-report"
            tabIndex={0}
            aria-label="性能验收JSON"
            className="fve:max-h-80 fve:overflow-auto fve:text-xs"
          >
            {JSON.stringify(report, null, 2)}
          </pre>
        </details>
      )}
      <ViewPage {...binding} />
    </div>
  );
}
// Observe completion without polling or allocating missing-report errors inside the benchmark.
function waitForReport(container: HTMLElement): Promise<HTMLElement> {
  return new Promise((resolve, reject) => {
    const ready = () => {
      const output = container.querySelector<HTMLElement>(
        '[data-testid="analysis-performance-report"]',
      );
      if (!output) return;
      observer.disconnect();
      clearTimeout(timer);
      resolve(output);
    };
    const observer = new MutationObserver(ready);
    const timer = setTimeout(() => {
      observer.disconnect();
      reject(new Error('等待性能验收报告超时'));
    }, 30000);
    observer.observe(container, { childList: true, subtree: true });
    ready();
  });
}

const meta = {
  id: 'view-engine-分析性能验收',
  title: 'View Engine/分析视图/性能验收',
  component: AnalysisPerformance,
  parameters: { layout: 'fullscreen' },
  render: args => <AnalysisPerformance key={String(args.stress)} {...args} />,
} satisfies Meta<typeof AnalysisPerformance>;
export default meta;
type Story = StoryObj<typeof meta>;
export const LocalPerformance: Story = {
  name: '100行_100字段_20筛选_P95',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await waitFor(
      () =>
        expect(
          canvas.getByRole('button', { name: '开始性能验收' }),
        ).toBeEnabled(),
      { timeout: 15000 },
    );
    await userEvent.click(canvas.getByRole('button', { name: '开始性能验收' }));
    const output = await waitForReport(canvasElement);
    const report = JSON.parse(output.textContent!);
    console.info('analysis-performance-report', JSON.stringify(report));
    await expect(report.error).toBeUndefined();
    await expect(report.sampleCount).toBe(30);
    await expect(report.requestsAfter).toBe(report.requestsBefore);
    if (report.environment.build === 'production') {
      await expect(report.input.p95Ms).toBeLessThanOrEqual(400);
      await expect(report.instanceSwitch.p95Ms).toBeLessThanOrEqual(450);
    }
  },
};
export const LargeResultCancellation: Story = {
  name: '10000行_21列_输入与取消',
  args: { stress: true },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await waitFor(
      () =>
        expect(
          canvas.getByRole('button', { name: '开始压力验收' }),
        ).toBeEnabled(),
      { timeout: 15000 },
    );
    await userEvent.click(canvas.getByRole('button', { name: '开始压力验收' }));
    const output = await waitForReport(canvasElement);
    const report = JSON.parse(output.textContent!);
    console.info('analysis-performance-report', JSON.stringify(report));
    await expect(report.error).toBeUndefined();
    await expect(report.passed).toBe(true);
    await expect(report.abortObserved).toBe(true);
    await expect(report.lateResponseIgnored).toBe(true);
    await expect(report.renderedDataRows).toBeLessThanOrEqual(100);
  },
};
