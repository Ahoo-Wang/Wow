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

import {
  defineDataset,
  type DefineDatasetSpec,
} from './define/defineDataset.js';
import type { WriteContext } from '../store/ViewStore.js';
import type { ViewSource } from './source.js';

/**
 * One dataset of a service's directory: where it lives, how it is titled,
 * and the version it was published at (`null` while it is not servable).
 */
export interface DatasetDirectoryEntry {
  contextName: string;
  name: string;
  title: string | null;
  version: string | null;
}

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
  /**
   * Where a tenant's administrators keep their wording over the datasets
   * (D91), such as wow-view-store's `WowDatasetWordings`: read whole before
   * each `refresh` registers, by definition id. A dataset's stored wording
   * stands in place of `overrides`. Given, each dataset registers with an
   * editor of its wording (`DatasetWordingEditor`).
   */
  wordings?: DatasetWordingStore;
}

/** A dataset's wording as stored, and the revision a write expects. */
export interface StoredDatasetWording {
  wording: DatasetOverrides;
  /** `'0'` for wording never written. */
  revision: string;
}

/** Where a dataset's wording is kept (D91); see `DatasetCatalog.wordings`. */
export interface DatasetWordingStore {
  /** Every dataset's stored wording, by definition id (`{context}.{dataset}`). */
  list(
    signal?: AbortSignal,
  ): Promise<ReadonlyMap<string, StoredDatasetWording>>;
  /** Writes one whole at the expected revision; answers the new revision. */
  set(
    dataset: string,
    wording: DatasetOverrides,
    revision: string,
    context: WriteContext,
  ): Promise<string>;
}

/**
 * What a dataset's wording editor reads and writes (D91): the fields the
 * descriptor derives, the wording in force, and a save that stores it and
 * registers the dataset again in the new words.
 */
export interface DatasetWordingEditor {
  /** The definition id, and the dataset's key in the store. */
  readonly dataset: string;
  /** The title the directory gives, before any wording. */
  readonly derivedTitle: string;
  /** The descriptor's fields in its order, labelled as derived before any wording. */
  readonly fields: readonly { name: string; label: string }[];
  /** The wording in force: the stored one, else the host's `overrides`. */
  readonly wording: DatasetOverrides;
  /** The revision a save expects. */
  readonly revision: string;
  /** Stores `wording` whole and registers the dataset again in it. */
  save(wording: DatasetOverrides, context: WriteContext): Promise<void>;
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
    wording?: DatasetWordingEditor;
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
  /** The wording stored when the store was last read (`DatasetCatalog.wordings`). */
  private stored: ReadonlyMap<string, StoredDatasetWording> = new Map();
  /** What each dataset was registered from, so a new wording needs no describe. */
  private readonly derived = new Map<
    string,
    {
      entry: DatasetDirectoryEntry;
      source: ViewSource;
      descriptor: Parameters<typeof defineDataset>[0];
    }
  >();

  constructor(
    private readonly engine: DatasetRegistrar,
    private readonly catalog: DatasetCatalog,
    /** Told of a dataset that could not be described or registered; it is left as it was. */
    private readonly onError: (
      entry: DatasetDirectoryEntry,
      error: unknown,
    ) => void = () => undefined,
    /** Told when the stored wording cannot be read; the wording read last stands. */
    private readonly onWordingError: (error: unknown) => void = () => undefined,
  ) {}

  /** The datasets served, in the directory's order. */
  get resources(): readonly DatasetResource[] {
    return [...this.held.values()];
  }

  async refresh(): Promise<readonly DatasetResource[]> {
    await this.readWordings();
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
        this.derived.delete(id);
      }
    await Promise.all(
      [...servable].map(([id, entry]) => this.serve(id, entry)),
    );
    const order = [...servable.keys()];
    return this.resources
      .slice()
      .sort((a, b) => order.indexOf(a.id) - order.indexOf(b.id));
  }

  /**
   * Reads the stored wording before a refresh registers; one that cannot be
   * read leaves what was read last, and is told for no dataset in particular.
   */
  private async readWordings(): Promise<void> {
    const store = this.catalog.wordings;
    if (!store) return;
    try {
      this.stored = await store.list();
    } catch (error) {
      this.onWordingError(error);
    }
  }

  /** The wording a dataset registers in: the stored one, else the host's. */
  private wordingOf(
    id: string,
    entry: DatasetDirectoryEntry,
  ): StoredDatasetWording {
    return (
      this.stored.get(id) ?? {
        wording: this.catalog.overrides?.(entry) ?? {},
        revision: '0',
      }
    );
  }

  private async serve(id: string, entry: DatasetDirectoryEntry): Promise<void> {
    const held = this.held.get(id);
    const { wording } = this.wordingOf(id, entry);
    const words = JSON.stringify(wording);
    if (held?.entry.version === entry.version && this.worded.get(id) === words)
      return;
    if (!held && this.engine.definitions.has(id)) return;
    try {
      const source = this.catalog.source(entry);
      const described = await source.describe?.();
      if (!described || described.notModified)
        throw new Error(`Dataset [${id}] does not describe itself.`);
      this.derived.set(id, {
        entry,
        source,
        descriptor: described.descriptor,
      });
      this.registerWorded(id);
    } catch (error) {
      this.onError(entry, error);
    }
  }

  /** Registers dataset `id` from what it was derived from, in the wording in force. */
  private registerWorded(id: string): void {
    const derived = this.derived.get(id);
    if (!derived) return;
    const { entry, source, descriptor } = derived;
    const current = this.wordingOf(id, entry);
    const title = entry.title ?? entry.name;
    const definition = defineDataset(descriptor, {
      id,
      source: id,
      title,
      ...current.wording,
    });
    this.engine.register({
      definition,
      source,
      ...(this.catalog.wordings
        ? { wording: this.editorOf(id, title, descriptor, current) }
        : {}),
    });
    this.held.set(id, { id, entry });
    this.worded.set(id, JSON.stringify(current.wording));
  }

  private editorOf(
    id: string,
    derivedTitle: string,
    descriptor: Parameters<typeof defineDataset>[0],
    current: StoredDatasetWording,
  ): DatasetWordingEditor {
    const plain = defineDataset(descriptor, {
      id,
      source: id,
      title: derivedTitle,
    });
    return {
      dataset: id,
      derivedTitle,
      fields: plain.fields.map(({ name, label }) => ({ name, label })),
      wording: current.wording,
      revision: current.revision,
      save: async (wording, context) => {
        const store = this.catalog.wordings;
        if (!store) return;
        const revision = await store.set(
          id,
          wording,
          current.revision,
          context,
        );
        this.stored = new Map(this.stored).set(id, { wording, revision });
        this.registerWorded(id);
      },
    };
  }
}
