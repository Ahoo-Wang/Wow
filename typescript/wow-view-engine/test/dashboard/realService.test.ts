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

// @vitest-environment node
import { expect, it, vi } from 'vitest';
import {
  FilterOperator,
  filter,
  type FilterExpression,
} from '@ahoo-wang/fetcher-wow';
import {
  ViewEngine,
  createFilterConfiguration,
  newFilterNode,
  type DashboardViewInstance,
  type ViewHost,
} from '../../src/index.js';
import { connectCompensation } from '../../examples/react/compensation/connection.js';

it.runIf(Boolean(process.env.FETCHER_DASHBOARD_REAL_BASE_URL))(
  'composes real snapshot definitions with scoped independent record and analysis positions',
  async () => {
    const baseURL = process.env.FETCHER_DASHBOARD_REAL_BASE_URL!;
    const origin = new URL(baseURL).origin;
    const requests: {
      path: string;
      filter?: FilterExpression;
      page?: number;
    }[] = [];
    const originalFetch = globalThis.fetch;
    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation(async (input, init) => {
        const request = new Request(input, init);
        const url = new URL(request.url);
        expect(url.origin).toBe(origin);
        const allowed =
          request.method === 'GET'
            ? ['/execution_failed/snapshot/schema', '/v3/api-docs']
            : request.method === 'POST'
              ? [
                  '/execution_failed/snapshot/paged',
                  '/execution_failed/snapshot/cursor',
                  '/execution_failed/snapshot/aggregation',
                ]
              : [];
        expect(allowed.includes(url.pathname)).toBe(true);
        if (request.method === 'POST') {
          const body = (await request.clone().json()) as {
            filter?: FilterExpression;
            pagination?: { index: number };
          };
          requests.push({
            path: url.pathname,
            filter: body.filter,
            page: body.pagination?.index,
          });
        }
        return originalFetch(request);
      });
    let engine: ViewEngine | undefined;
    try {
      const record = await connectCompensation({
        baseURL,
        kind: 'record',
        model: 'SNAPSHOT',
      });
      const analysis = await connectCompensation({
        baseURL,
        kind: 'analysis',
        model: 'SNAPSHOT',
      });
      const records = structuredClone(record.instances.instances[0]);
      if (records.kind !== 'record')
        throw new Error('Expected record template');
      records.config.pagination.size = 2;
      const overview = analysis.instances.instances.find(
        instance => instance.id === 'overview',
      )!;
      const statusField = record.definition.fields.find(
        field => field.field === 'state.status',
      )!;
      const statusFilter = (value: string) =>
        createFilterConfiguration({
          ...newFilterNode(FilterOperator.EQ),
          field: 'state.status',
          props: { value },
        });
      const dashboard: DashboardViewInstance = {
        id: 'real-dashboard',
        definitionId: 'real-dashboard-definition',
        kind: 'dashboard',
        title: '只读补偿仪表盘',
        scope: { type: 'personal' },
        revision: 'fixture-1',
        config: {
          schemaVersion: 1,
          panels: [
            {
              kind: 'view' as const,
              id: 'left',
              instanceId: records.id,
              layout: { x: 0, y: 0, w: 4, h: 18 },
            },
            {
              kind: 'view' as const,
              id: 'right',
              instanceId: records.id,
              layout: { x: 0, y: 0, w: 4, h: 18 },
            },
            {
              kind: 'view' as const,
              id: 'analysis',
              instanceId: overview.id,
              layout: { x: 0, y: 0, w: 4, h: 18 },
            },
          ],
          filters: [
            {
              id: 'status',
              filters: statusFilter('FAILED'),
              excludedPanelIds: [],
              bindings: ['left', 'right', 'analysis'].map(panelId => ({
                panelId,
                kind: 'fields',
                fields: { 'state.status': 'state.status' },
                semanticCompatibility: true,
              })),
            },
          ],
        },
      };
      const host: ViewHost = {
        instance: {
          load: async id => {
            const instance = [records, overview].find(value => value.id === id);
            if (!instance) throw new Error('Unknown fixture instance');
            return structuredClone(instance);
          },
        },
        definition: {
          load: async id => {
            const definition = [record.definition, analysis.definition].find(
              value => value.id === id,
            );
            if (!definition) throw new Error('Unknown fixture definition');
            return structuredClone(definition);
          },
        },
        resolveSource: id => {
          if (
            id !== record.definition.sourceId ||
            id !== analysis.definition.sourceId
          )
            throw new Error('Unknown fixture source');
          return record.source;
        },
      };
      engine = new ViewEngine({
        definitionId: dashboard.definitionId,
        definition: {
          id: dashboard.definitionId,
          title: dashboard.title,
          fields: [statusField],
          dashboard: true,
        },
        instances: { instances: [dashboard], defaultInstanceId: dashboard.id },
        host,
      });
      await engine.load();
      const runtime = engine.dashboard(dashboard.id);
      await vi.waitFor(
        () => {
          expect(
            Object.values(runtime.getSnapshot().panels).map(
              panel => panel.position?.getSnapshot().queryStatus,
            ),
          ).toEqual(['success', 'success', 'success']);
        },
        { timeout: 45_000, interval: 50 },
      );
      const { left, right, analysis: metric } = runtime.getSnapshot().panels;
      const leftPosition = left.position!,
        rightPosition = right.position!,
        metricPosition = metric.position!;
      if (
        leftPosition.kind !== 'record' ||
        rightPosition.kind !== 'record' ||
        metricPosition.kind !== 'analysis'
      )
        throw new Error('Wrong position kinds');
      expect(left.definition!.id).not.toBe(metric.definition!.id);
      expect(leftPosition.identity.instanceId).toBe(
        rightPosition.identity.instanceId,
      );
      expect(leftPosition.identity.id).not.toBe(rightPosition.identity.id);
      const expectStatus = (status: string) => {
        for (const position of [leftPosition, rightPosition]) {
          const session = position.getSnapshot();
          expect(session.rows.length).toBeGreaterThan(0);
          expect(
            session.rows.every(
              row => (row.state as { status?: string })?.status === status,
            ),
          ).toBe(true);
        }
      };
      expectStatus('FAILED');
      expect(requests).toHaveLength(3);
      const failedScope = filter.and([
        filter.matchAll(),
        filter.eq('state.status', 'FAILED'),
      ]);
      expect(
        requests.every(
          request =>
            JSON.stringify(request.filter) === JSON.stringify(failedScope),
        ),
      ).toBe(true);
      const siblingResult = rightPosition.getSnapshot().result;
      const analysisResult = metricPosition.getSnapshot().result;
      await leftPosition.commands.setPage(2);
      expect(leftPosition.getSnapshot().page).toBe(2);
      expect(rightPosition.getSnapshot().page).toBe(1);
      expect(rightPosition.getSnapshot().result).toBe(siblingResult);
      expect(metricPosition.getSnapshot().result).toBe(analysisResult);
      expect(requests).toHaveLength(4);
      expect(requests.at(-1)?.page).toBe(2);
      await runtime.refresh('left');
      expect(requests).toHaveLength(5);
      expect(rightPosition.getSnapshot().result).toBe(siblingResult);
      expect(metricPosition.getSnapshot().result).toBe(analysisResult);
      expectStatus('FAILED');
      runtime.setFilter('status', statusFilter('SUCCEEDED'));
      expect(runtime.getSnapshot().pending).toBe(true);
      expect(requests).toHaveLength(5);
      await expect(engine.save(dashboard.id)).rejects.toThrow();
      expect(requests).toHaveLength(5);
      expect(host.instance?.save).toBeUndefined();
      await runtime.apply();
      expectStatus('SUCCEEDED');
      expect(requests).toHaveLength(8);
      const succeededScope = filter.and([
        filter.matchAll(),
        filter.eq('state.status', 'SUCCEEDED'),
      ]);
      expect(
        requests
          .slice(5)
          .every(
            request =>
              JSON.stringify(request.filter) === JSON.stringify(succeededScope),
          ),
      ).toBe(true);
      expect(leftPosition.getSnapshot().page).toBe(1);
      expect(rightPosition.getSnapshot().page).toBe(1);
      expect(metricPosition.getSnapshot().queryStatus).toBe('success');
      process.stdout.write(
        JSON.stringify({
          businessAggregate: 'compensation.execution_failed',
          frontendDefinitions: 2,
          panels: 3,
          repeatedRecordPositions: 2,
          queryRequests: requests.length,
          pagedRequests: requests.filter(request =>
            request.path.endsWith('/paged'),
          ).length,
          aggregationRequests: requests.filter(request =>
            request.path.endsWith('/aggregation'),
          ).length,
          scopedRecordsVerified: true,
          localIsolationVerified: true,
          realWrites: 0,
        }) + '\n',
      );
    } finally {
      engine?.dispose();
      fetchSpy.mockRestore();
    }
  },
  120_000,
);
