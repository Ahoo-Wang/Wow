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
import { createServer, type RequestListener } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterEach, expect, it } from 'vitest';
import { Fetcher } from '@ahoo-wang/fetcher';
import {
  aggregation,
  FilterOperator,
  AggregationFunction,
  AggregationExpressionType,
  AggregationExpressionOperator,
} from '@ahoo-wang/fetcher-wow';
import {
  compileAnalysis,
  validateAnalysisResult,
  type AnalysisViewConfig,
} from '../src/index.js';
import { connectCompensation } from '../examples/react/compensation/connection.js';

const servers: ReturnType<typeof createServer>[] = [];
afterEach(async () => {
  for (const server of servers.splice(0)) {
    server.closeAllConnections();
    await new Promise<void>(resolve => server.close(() => resolve()));
  }
});
const scalar = (valueTypes: string[], capabilities: string[]) => ({
  kind: 'SCALAR',
  valueTypes,
  capabilities,
  masked: false,
  properties: {},
});
const schema = {
  model: 'SNAPSHOT',
  root: {
    kind: 'OBJECT',
    masked: false,
    properties: {
      state: {
        kind: 'OBJECT',
        masked: false,
        properties: {
          status: {
            ...scalar(['STRING'], ['EXACT_MATCH', 'AGGREGATE_TERMS']),
            enumValues: ['FAILED', 'SUCCEEDED'],
          },
          retryState: {
            kind: 'OBJECT',
            masked: false,
            properties: {
              retries: scalar(['INTEGER'], ['RANGE', 'AGGREGATE_NUMERIC']),
            },
          },
        },
      },
    },
  },
};
async function serve(handler: RequestListener) {
  const server = createServer(handler);
  servers.push(server);
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  return `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
}
it('loads actual Schema then reuses the authenticated Wow client and exact aggregation path', async () => {
  const requests: {
    url?: string;
    method?: string;
    auth?: string;
    body: string;
  }[] = [];
  const url = await serve(async (req, res) => {
    let body = '';
    for await (const chunk of req) body += chunk;
    requests.push({
      url: req.url,
      method: req.method,
      auth: req.headers.authorization,
      body,
    });
    res.setHeader('content-type', 'application/json');
    res.end(
      JSON.stringify(req.url?.endsWith('/schema') ? schema : [{ records: 7 }]),
    );
  });
  const connection = await connectCompensation({
    baseURL: url,
    fetcher: new Fetcher({
      baseURL: url,
      headers: {
        Authorization: 'Bearer fixture-token',
        'Content-Type': 'application/json',
      },
    }),
  });
  expect(connection.definition.fields.map(field => field.field)).toContain(
    'state.retryState.retries',
  );
  const rows = await connection.source.aggregate({
    metrics: [aggregation.count('records')],
    limit: 1,
  });
  expect(rows).toEqual([{ records: 7 }]);
  expect(requests.map(r => [r.method, r.url, r.auth])).toEqual([
    ['GET', '/execution_failed/snapshot/schema', 'Bearer fixture-token'],
    ['POST', '/execution_failed/snapshot/aggregation', 'Bearer fixture-token'],
  ]);
  expect(JSON.parse(requests[1].body)).toEqual({
    metrics: [{ type: 'COUNT', alias: 'records' }],
    limit: 1,
  });
});
it('rejects wrong models and aborts pending Schema requests', async () => {
  const wrong = await serve((_req, res) => {
    res.setHeader('content-type', 'application/json');
    res.end(JSON.stringify({ ...schema, model: 'EVENT_STREAM' }));
  });
  await expect(connectCompensation({ baseURL: wrong })).rejects.toThrow(
    /SNAPSHOT/,
  );
  const url = await serve(() => {});
  const controller = new AbortController();
  const pending = connectCompensation({
    baseURL: url,
    signal: controller.signal,
  });
  controller.abort();
  await expect(pending).rejects.toThrow();
});

it('compiles relative event scope through EventStreamQueryClient and propagates aggregation failures and cancellation', async () => {
  const paths: string[] = [];
  let received: Record<string, unknown> = {};
  const eventSchema = {
    model: 'EVENT_STREAM',
    root: {
      kind: 'OBJECT',
      masked: false,
      properties: {
        body: {
          kind: 'ARRAY',
          masked: false,
          capabilities: ['ELEMENT_SCOPE'],
          items: {
            kind: 'OBJECT',
            masked: false,
            properties: {
              name: scalar(['STRING'], ['EXACT_MATCH', 'AGGREGATE_TERMS']),
            },
          },
        },
      },
    },
  };
  let mode = 'success';
  const url = await serve(async (req, res) => {
    paths.push(req.url!);
    if (req.url?.endsWith('/schema')) {
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify(eventSchema));
      return;
    }
    if (mode === 'pending') return;
    res.setHeader('content-type', 'application/json');
    if (mode === 'error') {
      res.statusCode = 503;
      res.end('{}');
      return;
    }
    let body = '';
    for await (const chunk of req) body += chunk;
    received = JSON.parse(body);
    res.end(JSON.stringify([{ event: 'CompensationPrepared', records: 4 }]));
  });
  const connection = await connectCompensation({
    baseURL: url,
    model: 'EVENT_STREAM',
  });
  expect(connection.instances.instances.map(instance => instance.id)).toEqual([
    'overview',
    'event-types',
  ]);
  const config = connection.instances.instances.find(
    instance => instance.id === 'event-types',
  )!.config;
  const plan = compileAnalysis(config, {
    fields: connection.definition.fields,
    capability: connection.definition.analysis!,
  }).plan!;
  expect((await connection.source.aggregate(plan.query))[0].records).toBe(4);
  expect(paths).toEqual([
    '/execution_failed/event/schema',
    '/execution_failed/event/aggregation',
  ]);
  expect(received.elements).toMatchObject([{ path: 'body' }]);
  expect(received.groupBy).toEqual([
    { type: 'TERMS', field: 'name', alias: 'event' },
  ]);
  mode = 'error';
  await expect(connection.source.aggregate(plan.query)).rejects.toThrow();
  mode = 'pending';
  const controller = new AbortController();
  const pending = connection.source.aggregate(
    plan.query,
    undefined,
    controller,
  );
  controller.abort();
  await expect(pending).rejects.toThrow();
});

// Explicit opt-in only. Performs Schema GETs and read-only aggregation POSTs.
it.runIf(!!process.env.COMPENSATION_DEV_URL)(
  'integrates compiled templates, expressions and element scope with dev',
  async () => {
    for (const model of ['SNAPSHOT', 'EVENT_STREAM'] as const) {
      const connection = await connectCompensation({
        baseURL: process.env.COMPENSATION_DEV_URL!,
        model,
      });
      const configs = connection.instances.instances.map(instance => ({
        id: instance.id,
        config: instance.config,
      }));
      if (model === 'SNAPSHOT') {
        const overview = configs.find(item => item.id === 'overview')!.config;
        configs.push({
          id: 'expression',
          config: {
            ...overview,
            metrics: [
              {
                id: 'expression',
                alias: 'adjusted',
                title: '每条记录重试次数加一的平均值',
                component: { name: 'numeric' },
                props: { function: AggregationFunction.AVG },
                expression: {
                  type: AggregationExpressionType.BINARY,
                  operator: AggregationExpressionOperator.ADD,
                  left: {
                    type: AggregationExpressionType.FIELD,
                    field: 'state.retryState.retries',
                  },
                  right: { type: AggregationExpressionType.CONSTANT, value: 1 },
                },
              },
            ],
          },
        });
      }
      for (const { id, config } of configs) {
        const compiled = compileAnalysis(config as AnalysisViewConfig, {
          fields: connection.definition.fields,
          capability: connection.definition.analysis!,
          timeZone: connection.definition.timeZone,
        });
        expect(compiled.errors, `${model}/${id}`).toEqual([]);
        const rows = await connection.source.aggregate(compiled.plan!.query);
        expect(
          validateAnalysisResult(rows, compiled.plan!).errors,
          `${model}/${id}`,
        ).toEqual([]);
        // Only aggregate counts and test metadata are logged; no raw events or payloads.
        process.stdout.write(
          JSON.stringify({
            model,
            id,
            rows: rows.length,
            metricAliases: compiled.plan!.query.metrics.map(
              metric => metric.alias,
            ),
            ...(id === 'overview' ? { values: rows[0] } : {}),
          }) + '\n',
        );
      }
    }
  },
  180000,
);

it('builds snapshot data views from schema and delegates paging to the Wow API', async () => {
  const recordSchema = structuredClone(schema);
  Object.assign(recordSchema.root.properties, {
    aggregateId: scalar(['STRING'], ['EXACT_MATCH', 'SORT']),
    eventTime: {
      ...scalar(['INTEGER'], ['RANGE', 'SORT']),
      semanticType: { type: 'TEMPORAL_EPOCH', timeUnit: 'MILLISECONDS' },
    },
  });
  const requests: { path: string; body: string }[] = [];
  const url = await serve(async (req, res) => {
    let body = '';
    for await (const chunk of req) body += chunk;
    requests.push({ path: req.url!, body });
    res.setHeader('content-type', 'application/json');
    res.end(
      JSON.stringify(
        req.url?.endsWith('/schema')
          ? recordSchema
          : {
              total: 21,
              list: [{ aggregateId: 'record-21', state: { status: 'FAILED' } }],
            },
      ),
    );
  });
  const connection = await connectCompensation({
    baseURL: url,
    kind: 'record',
  });
  expect(connection.definition.record?.rowKey).toBe('aggregateId');
  expect(
    connection.definition.fields.find(field => field.field === 'state.status')
      ?.sortable,
  ).toBe(false);
  expect(
    connection.definition.fields.find(field => field.field === 'eventTime')
      ?.cellRenderer?.name,
  ).toBe('date-time');
  const instance = connection.instances.instances[0];
  expect(instance.kind).toBe('record');
  if (instance.kind !== 'record') throw new Error('Expected a data view');
  const query = {
    filter: { op: FilterOperator.EQ, field: 'state.status', value: 'FAILED' },
    sort: instance.config.sort,
    pagination: { index: 2, size: 20 },
  };
  const page = await connection.source.paged(query);
  expect(page.list).toHaveLength(1);
  expect(requests[1].path).toBe('/execution_failed/snapshot/paged');
  expect(JSON.parse(requests[1].body)).toEqual(query);
  await expect(
    connectCompensation({
      baseURL: url,
      kind: 'record',
      model: 'EVENT_STREAM',
    }),
  ).rejects.toThrow('快照');
});

it.runIf(!!process.env.COMPENSATION_DEV_URL)(
  'integrates snapshot data paging and status filtering with dev',
  async () => {
    const connection = await connectCompensation({
      baseURL: process.env.COMPENSATION_DEV_URL!,
      kind: 'record',
    });
    const instance = connection.instances.instances[0];
    if (instance.kind !== 'record') throw new Error('Expected a data view');
    const query = {
      filter: { op: FilterOperator.MATCH_ALL },
      sort: instance.config.sort,
      pagination: { index: 1, size: 20 },
    };
    const first = await connection.source.paged(query);
    const second = await connection.source.paged({
      ...query,
      pagination: { index: 2, size: 20 },
    });
    expect(first.list.length).toBeGreaterThan(0);
    expect(first.list.length).toBeLessThanOrEqual(20);
    expect(second.list.length).toBeLessThanOrEqual(20);
    const value = connection.definition.fields
      .find(field => field.field === 'state.status')
      ?.options?.find(option => option.label === '已成功')?.value;
    expect(value).toBeDefined();
    const filtered = await connection.source.paged({
      ...query,
      filter: { op: FilterOperator.EQ, field: 'state.status', value },
    });
    expect(filtered.list.length).toBeGreaterThan(0);
    for (const row of filtered.list) expect(row.state?.status).toBe(value);
    process.stdout.write(
      JSON.stringify({
        model: 'SNAPSHOT',
        kind: 'record',
        firstPage: first.list.length,
        secondPage: second.list.length,
        filteredPage: filtered.list.length,
        total: first.total,
        filteredTotal: filtered.total,
      }) + '\n',
    );
  },
  90000,
);
