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

import type { DatasetDirectoryEntry } from '@ahoo-wang/wow-client';
import {
  defineDataset,
  type DefineDatasetSpec,
} from './define/defineDataset.js';
import type { ViewSource } from './source.js';

/** What `DatasetResources` reads: the dataset directory, and each dataset's source. */
export interface DatasetCatalog {
  /** The datasets the service serves (`GET /dataset`). */
  list(): Promise<readonly DatasetDirectoryEntry[]>;
  /** The source of one, such as wow-client's `DatasetQueryClient`; it must describe itself. */
  source(entry: DatasetDirectoryEntry): ViewSource;
  /**
   * The host's words over one dataset (D86): its title, its noun, and its
   * fields' labels, order and narrowing (`DefineDatasetSpec.fields`).
   */
  overrides?(entry: DatasetDirectoryEntry): DatasetOverrides | undefined;
}

/** What a host may say over a dataset's derived definition; see `DatasetCatalog.overrides`. */
export type DatasetOverrides = Partial<
  Pick<DefineDatasetSpec, 'title' | 'recordNoun' | 'fields'>
>;

/** The part of an engine `DatasetResources` drives (`ViewEngine` is one). */
export interface DatasetRegistrar {
  readonly definitions: ReadonlyMap<string, unknown>;
  register(resource: {
    definition: ReturnType<typeof defineDataset>;
    source: ViewSource;
  }): void;
  unregister(definitionId: string): boolean;
}

/** A dataset served, as registered: its directory entry and its definition id. */
export interface DatasetResource {
  id: string;
  entry: DatasetDirectoryEntry;
}

/**
 * Keeps an engine's datasets in step with a service's directory (D85): each
 * `refresh` registers a dataset the directory newly lists, registers again
 * one whose descriptor version changed, and removes one it no longer lists
 * or whose schema does not compile (`version` `null`). A dataset's
 * definition id is its name, `{context}.{dataset}`, and its title the
 * directory's; a definition the host declared in code under that id wins
 * and the dataset is left out.
 *
 * `refresh` answers the datasets served, which a host binds routes to
 * (`bind`) so they appear in its navigation: opening one is how a reader
 * starts a view of it.
 */
export class DatasetResources {
  private readonly held = new Map<string, DatasetResource>();
  /** The overrides each dataset was registered with, as JSON: new words register it again. */
  private readonly worded = new Map<string, string>();

  constructor(
    private readonly engine: DatasetRegistrar,
    private readonly catalog: DatasetCatalog,
    /** Told of a dataset that could not be described or registered; it is left as it was. */
    private readonly onError: (
      entry: DatasetDirectoryEntry,
      error: unknown,
    ) => void = () => undefined,
  ) {}

  /** The datasets served, in the directory's order. */
  get resources(): readonly DatasetResource[] {
    return [...this.held.values()];
  }

  async refresh(): Promise<readonly DatasetResource[]> {
    const listed = await this.catalog.list();
    const servable = new Map<string, DatasetDirectoryEntry>();
    for (const entry of listed)
      if (entry.version !== null)
        servable.set(`${entry.contextName}.${entry.name}`, entry);
    for (const id of [...this.held.keys()])
      if (!servable.has(id)) {
        this.engine.unregister(id);
        this.held.delete(id);
        this.worded.delete(id);
      }
    await Promise.all(
      [...servable].map(([id, entry]) => this.serve(id, entry)),
    );
    const order = [...servable.keys()];
    return this.resources
      .slice()
      .sort((a, b) => order.indexOf(a.id) - order.indexOf(b.id));
  }

  private async serve(id: string, entry: DatasetDirectoryEntry): Promise<void> {
    const held = this.held.get(id);
    const overrides = this.catalog.overrides?.(entry);
    const words = JSON.stringify(overrides ?? null);
    if (held?.entry.version === entry.version && this.worded.get(id) === words)
      return;
    if (!held && this.engine.definitions.has(id)) return;
    try {
      const source = this.catalog.source(entry);
      const described = await source.describe?.();
      if (!described || described.notModified)
        throw new Error(`Dataset [${id}] does not describe itself.`);
      const definition = defineDataset(described.descriptor, {
        id,
        source: id,
        title: entry.title ?? entry.name,
        ...overrides,
      });
      this.engine.register({ definition, source });
      this.held.set(id, { id, entry });
      this.worded.set(id, words);
    } catch (error) {
      this.onError(entry, error);
    }
  }
}
