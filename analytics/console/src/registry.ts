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

import { fetcher, type Fetcher } from '@ahoo-wang/fetcher';

/** How one field is protected, as a reviewer decided; no level is "not sensitive". */
export interface FieldSensitivity {
  level?: 'DISPLAY' | 'CONFIDENTIAL' | null;
  keepPrefix?: number;
  keepSuffix?: number;
}

/** A dataset of the registry, as its snapshot holds it. */
export interface RegistryDataset {
  id: string;
  table: string;
  /** The draft's declaration, in the format of a dataset file. */
  document: Record<string, unknown>;
  reviews: Record<string, FieldSensitivity>;
  published: Record<string, unknown> | null;
  publishedVersion: number;
}

/** The fields a declaration names, in its order. */
export function fieldsOf(document: Record<string, unknown>): string[] {
  const fields = document.fields;
  return fields && typeof fields === 'object' ? Object.keys(fields) : [];
}

/** Sends one request to the analytics server and answers its JSON body. */
export type Send = (
  method: string,
  path: string,
  body?: unknown,
) => Promise<unknown>;

/** `send` over a fetcher: a refused request rejects with the server's message. */
export function fetcherSend(target: Fetcher = fetcher): Send {
  return async (method, path, body) => {
    const response = await target.fetch<Response>(path, {
      method,
      ...(body === undefined ? {} : { body: body as object }),
      // A command answers once its snapshot is written, so the list that
      // follows already shows it.
      headers: { 'Command-Wait-Stage': 'SNAPSHOT' },
    });
    const text = await response.text();
    const json: unknown = text ? JSON.parse(text) : null;
    if (!response.ok) {
      const message =
        json && typeof json === 'object' && 'errorMsg' in json
          ? String((json as { errorMsg: unknown }).errorMsg)
          : `${response.status} ${response.statusText}`;
      throw new Error(message);
    }
    return json;
  };
}

const BASE = '/dataset-registry/dataset';

/**
 * The dataset registry's commands and its list (analytics design §5): an
 * admin drafts a dataset from a table, revises its declaration, reviews
 * each field's sensitivity, publishes and retires it. The server allows
 * them to `analytics-admin` only.
 */
export class RegistryClient {
  constructor(private readonly send: Send = fetcherSend()) {}

  async list(): Promise<RegistryDataset[]> {
    const states = await this.send('POST', `${BASE}/snapshot/list/state`, {
      filter: { op: 'MATCH_ALL' },
      sort: [{ field: 'id', direction: 'ASC' }],
      limit: 500,
    });
    return (states as RegistryDataset[]) ?? [];
  }

  draft(id: string, table: string, source?: string): Promise<unknown> {
    return this.send('POST', `${BASE}/${encodeURIComponent(id)}/draft`, {
      table,
      ...(source ? { source } : {}),
    });
  }

  revise(id: string, document: Record<string, unknown>): Promise<unknown> {
    return this.send('PUT', `${BASE}/${encodeURIComponent(id)}/revise`, {
      document,
    });
  }

  review(
    id: string,
    fields: Record<string, FieldSensitivity>,
  ): Promise<unknown> {
    return this.send('PUT', `${BASE}/${encodeURIComponent(id)}/sensitivity`, {
      fields,
    });
  }

  publish(id: string): Promise<unknown> {
    return this.send('POST', `${BASE}/${encodeURIComponent(id)}/publish`, {});
  }

  retire(id: string): Promise<unknown> {
    return this.send('POST', `${BASE}/${encodeURIComponent(id)}/retire`, {});
  }
}
