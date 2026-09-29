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
  textKeysIn,
  type Issue,
  type TextResolver,
  type ViewDefinition,
} from '../model/index.js';
import { issue } from '../filter/index.js';
import { sayDefinition, type DefinitionRegistry } from './definitions.js';
import type { OptionSource, ViewSource } from './source.js';
import { EngineText } from './text.js';
import { defaultIssueReporter, type IssueReporter } from './issueReport.js';
import { ViewCommandError } from './write.js';

/** What the engine's resources are read from; see `ViewEngineOptions`. */
export interface ResourceHost {
  resources: readonly {
    definition: ViewDefinition;
    source?: ViewSource;
  }[];
  resolveOptions?(key: string): OptionSource;
  text?(key: string): string | undefined;
  onIssue?(issue: Issue): void;
}

/**
 * The half of the engine that is what the host registered
 * (host-integration.md 4): each definition's source, by the key its
 * definitions name it by, and the words its keys are said in
 * (`EngineText`, D2).
 */
export abstract class EngineResources {
  /** The definitions as registered, keys and all. */
  protected abstract readonly registry: DefinitionRegistry;
  /** The words the definitions' keys are said in; see `EngineText`. */
  protected readonly text: EngineText;
  private readonly sources = new Map<string, ViewSource>();
  /** What was told to lack words, by language, code and key; see `setText`. */
  private readonly lacking = new Set<string>();
  /** Where findings go: the host's `onIssue`, or the development default. */
  private readonly reporter: IssueReporter | undefined;

  constructor(private readonly host: ResourceHost) {
    // Called on the host's object, as a method.
    this.reporter = host.onIssue
      ? found => host.onIssue?.(found)
      : defaultIssueReporter();
    // Called on the host's object, which a catalogue's method may read.
    this.text = new EngineText(host.text && (key => host.text?.(key)), () =>
      textKeysIn([...this.registry.definitions.values()]),
    );
    for (const { definition, source } of host.resources)
      if (
        source &&
        definition.kind === 'data' &&
        !this.sources.has(definition.source)
      )
        this.sources.set(definition.source, source);
  }

  /**
   * Every registered definition, by id, in the words in force: its keys
   * (`text(key)`) said as `setText` last said them.
   */
  get definitions(): ReadonlyMap<string, ViewDefinition> {
    return this.text.say(this.registry.definitions);
  }

  /**
   * Says the definitions' keys in another language from now on: every open
   * view redraws in it, and nothing is rebuilt (host-integration.md 3.1,
   * D2). The Provider calls this as its locale or messages change, naming
   * the `language` they are in.
   *
   * The words set are checked on their own: a key they lack reads as
   * itself (`definition.text.unknown`), or in the words the engine started
   * with (`definition.text.fallback`) — a Chinese page showing an English
   * label is a gap too. A fallback is told only of words that say any of
   * the definitions' keys at all: words that say none (a Provider with no
   * catalogue of the definitions') leave them to the starting words, as
   * the host meant. Each key is told once per language, and again once
   * that language had it and lacks it again.
   */
  setText(text: TextResolver | undefined, language = ''): void {
    if (!this.text.set(text) || !text) return;
    const findings = [...this.registry.definitions.values()].flatMap(
      definition =>
        sayDefinition(definition, text).findings.map(found => ({
          found,
          definition: definition.id,
          key: String(found.params?.key),
        })),
    );
    const says = this.text.saysAny(text);
    const lacking = new Set<string>();
    for (const { found, definition, key } of findings) {
      const filled = this.text.resolve(key) !== undefined;
      if (filled && !says) continue;
      const code = filled
        ? 'definition.text.fallback'
        : 'definition.text.unknown';
      const told = `${language}\u0000${code}\u0000${key}`;
      lacking.add(told);
      if (this.lacking.has(told)) continue;
      this.lacking.add(told);
      this.report({ ...found, code }, definition);
    }
    // What this language now has is forgotten, so lacking it again is told.
    for (const told of this.lacking)
      if (told.startsWith(`${language}\u0000`) && !lacking.has(told))
        this.lacking.delete(told);
  }

  /** `value` in the words in force: a definition, a list, a title. */
  say<T>(value: T): T {
    return this.text.say(value);
  }

  /** Told whenever `setText` changes the words. */
  subscribeText(listener: () => void): () => void {
    return this.text.subscribe(listener);
  }

  /**
   * A finding with nobody to reject (`onIssue`), with the resource it is
   * about where there is one.
   */
  protected report(found: Issue, resource?: string): void {
    this.reporter?.(found, resource);
  }

  /** Whether a resource registered a source under `key`. */
  protected hasSource(key: string): boolean {
    return this.sources.has(key);
  }

  /** The source registered for a definition's `source` key. */
  resolveSource(key: string): ViewSource {
    const source = this.sources.get(key);
    if (!source)
      throw new ViewCommandError(
        issue('runtime.source.unresolved', [], { source: key }),
      );
    return source;
  }

  resolveOptions(key: string): OptionSource {
    const resolve = this.host.resolveOptions;
    if (!resolve)
      throw new ViewCommandError(
        issue('runtime.options.unresolved', [], { source: key }),
      );
    return resolve(key);
  }
}
