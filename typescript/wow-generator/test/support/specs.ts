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

/**
 * Minimal OpenAPI documents for the probes: the smallest input that shows
 * each problem the pre-release review found.
 */

type Json = Record<string, any>;

const jsonContent = (schema: Json) => ({
  content: { 'application/json': { schema } },
});

/** A plain OpenAPI document. */
export function document(
  paths: Json,
  schemas: Json = {},
  extra: Json = {},
): Json {
  return {
    openapi: '3.0.3',
    info: { title: 'Probe', version: '1' },
    paths,
    components: { schemas, ...(extra.components ?? {}) },
    ...Object.fromEntries(
      Object.entries(extra).filter(([key]) => key !== 'components'),
    ),
  };
}

/** A GET operation of tag `Items` answering 200 with a JSON schema. */
export function getOperation(
  operationId: string,
  schema: Json = { type: 'string' },
  extra: Json = {},
): Json {
  return {
    get: {
      tags: ['Items'],
      operationId,
      responses: { '200': { description: 'OK', ...jsonContent(schema) } },
      ...extra,
    },
  };
}

export interface WowAggregateOptions {
  context?: string;
  aggregate?: string;
  /** The aggregate's route segment; its name by default. */
  resource?: string;
  /** Command names, each with a body schema named after it. */
  commands?: string[];
  /** Event names and titles, each with a body schema named after it. */
  events?: { name: string; title?: string; body?: Json }[];
  /** Leave out the snapshot_state.single operation. */
  withoutState?: boolean;
  /** Replaces the event stream schema. */
  eventStream?: Json;
  /** Replaces the snapshot.count request body. */
  countBody?: Json | null;
  /** Declare the tag in the top-level tags. */
  declareTag?: boolean;
}

function pascal(name: string): string {
  return name
    .split(/[^A-Za-z0-9]+/)
    .filter(Boolean)
    .map(part => part[0].toUpperCase() + part.slice(1))
    .join('');
}

/**
 * A Wow service document with one aggregate: commands, state, query fields
 * and events, laid out the way wow-openapi writes them.
 */
export function wowDocument(options: WowAggregateOptions = {}): Json {
  const context = options.context ?? 'shop';
  const aggregate = options.aggregate ?? 'order';
  const resource = options.resource ?? aggregate;
  const tag = `${context}.${aggregate}`;
  const commands = options.commands ?? ['create_order'];
  const events = options.events ?? [{ name: 'order_created' }];
  const schemas: Json = {
    [`${context}.${aggregate}.${pascal(aggregate)}State`]: {
      type: 'object',
      properties: { id: { type: 'string' } },
      required: ['id'],
    },
    [`${context}.${aggregate}.${pascal(aggregate)}AggregatedFields`]: {
      type: 'string',
      enum: ['id'],
    },
  };
  const paths: Json = {};
  for (const command of commands) {
    const key = `${context}.${aggregate}.${pascal(command)}`;
    schemas[key] = {
      type: 'object',
      properties: { id: { type: 'string' } },
      required: ['id'],
    };
    paths[`/${resource}/${command}`] = {
      post: {
        tags: [tag],
        operationId: `${tag}.${command}`,
        summary: command,
        requestBody: jsonContent({ $ref: `#/components/schemas/${key}` }),
        responses: { '200': { $ref: '#/components/responses/wow.CommandOk' } },
      },
    };
  }
  const eventSchemas = events.map(event => {
    const key = `${context}.${aggregate}.${pascal(event.name)}`;
    schemas[key] = event.body ?? {
      type: 'object',
      properties: { id: { type: 'string' } },
      required: ['id'],
    };
    return {
      type: 'object',
      title: event.title ?? event.name,
      properties: {
        name: { type: 'string', const: event.name },
        body: { $ref: `#/components/schemas/${key}` },
      },
    };
  });
  const streamKey = `${context}.${aggregate}.${pascal(aggregate)}AggregatedDomainEventStream`;
  schemas[streamKey] = options.eventStream ?? {
    type: 'object',
    properties: { body: { type: 'array', items: { anyOf: eventSchemas } } },
  };
  if (!options.withoutState) {
    paths[`/${resource}/snapshot/single/state`] = {
      post: {
        tags: [tag],
        operationId: `${tag}.snapshot_state.single`,
        responses: {
          '200': jsonContent({
            $ref: `#/components/schemas/${context}.${aggregate}.${pascal(aggregate)}State`,
          }),
        },
      },
    };
  }
  const countBody =
    options.countBody === undefined
      ? {
          content: {},
          'x-wow-query-fields': {
            $ref: `#/components/schemas/${context}.${aggregate}.${pascal(aggregate)}AggregatedFields`,
          },
        }
      : options.countBody;
  paths[`/tenant/{tenantId}/${resource}/snapshot/count`] = {
    post: {
      tags: [tag],
      operationId: `${tag}.tenant.snapshot.count`,
      ...(countBody === null ? {} : { requestBody: countBody }),
      responses: { '200': jsonContent({ type: 'integer' }) },
    },
  };
  paths[`/${resource}/snapshot/count`] = {
    post: {
      tags: [tag],
      operationId: `${tag}.snapshot.count`,
      ...(countBody === null ? {} : { requestBody: countBody }),
      responses: { '200': jsonContent({ type: 'integer' }) },
    },
  };
  paths[`/${resource}/{id}/event/list`] = {
    post: {
      tags: [tag],
      operationId: `${tag}.event.list_query`,
      responses: {
        '200': jsonContent({
          type: 'array',
          items: { $ref: `#/components/schemas/${streamKey}` },
        }),
      },
    },
  };
  return {
    openapi: '3.0.1',
    info: { title: 'Probe', version: '1', 'x-wow-context-alias': context },
    ...(options.declareTag === false ? {} : { tags: [{ name: tag }] }),
    paths,
    components: {
      schemas,
      responses: {
        'wow.CommandOk': jsonContent({ type: 'object' }),
      },
    },
  };
}
