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
  type RuntimeLimits,
  type TextResolver,
  type ViewDefinition,
} from '../model/index.js';
import { issue, type FieldKindRegistry } from '../filter/index.js';
import type { DatasetWordingEditor } from './datasetResources.js';
import { DefinitionRegistry, sayDefinition } from './definitions.js';
import {
  recordSource,
  type OptionSource,
  type RecordSource,
} from './source.js';
import { EngineText } from './text.js';
import {
  defaultIssueReporter,
  type IssueReporter,
} from './failure/issueReport.js';
import type { ViewEngineOptions, ViewResource } from './viewEngineOptions.js';
import { ViewCommandError } from './write.js';

/** What the engine's resources are read from: the host's options. */
type ResourceHost = Pick<
  ViewEngineOptions,
  'resources' | 'resolveOptions' | 'text' | 'onIssue'
>;

/**
 * What the host registered (host-integration.md 4), held by the engine
 * rather than inherited by it: the definitions and what was said of them
 * (`DefinitionRegistry`), each definition's source by the key its
 * definitions name it by, each dataset's wording editor, the words the
 * keys are checked against (`EngineText`), and where findings go. The
 * engine's members of the same names hand on to these.
 */
export class Resources {
  /** The definitions, judged as they are registered. */
  readonly registry: DefinitionRegistry;
  /** The words the definitions' keys are checked against; see `EngineText`. */
  private readonly text: EngineText;
  private readonly sources = new Map<string, RecordSource>();
  /** Each dataset's wording editor, by definition id. */
  private readonly wordings = new Map<string, DatasetWordingEditor>();
  /** What was told to lack words, by language, code and key; see `setText`. */
  private readonly lacking = new Set<string>();
  /** Where findings go: the host's `onIssue`, or the development default. */
  private readonly reporter: IssueReporter | undefined;
  /** The ids a binding named that no resource registers, each told once. */
  private readonly unbound = new Set<string>();

  constructor(
    private readonly host: ResourceHost,
    kinds: FieldKindRegistry,
    limits: RuntimeLimits,
  ) {
    // Called on the host's object, as a method.
    this.reporter = host.onIssue
      ? found => host.onIssue?.(found)
      : defaultIssueReporter();
    // Called on the host's object, which a catalogue's method may read.
    this.text = new EngineText(host.text && (key => host.text?.(key)), () =>
      textKeysIn([...this.registry.definitions.values()]),
    );
    // The sources before the registry, which judges each data definition
    // by whether its source is registered.
    for (const { definition, source, wording } of host.resources) {
      if (
        source &&
        definition.kind === 'data' &&
        !this.sources.has(definition.source)
      )
        this.sources.set(definition.source, recordSource(source));
      if (wording) this.wordings.set(definition.id, wording);
    }
    this.registry = new DefinitionRegistry(
      {
        definitions: host.resources.map(({ definition }) => definition),
        hasSource: key => this.sources.has(key),
        ...(host.text ? { text: (key: string) => host.text?.(key) } : {}),
      },
      kinds,
      limits,
      (found, resource) => this.report(found, resource),
    );
  }

  /** See `ViewEngine.definitions`. */
  get definitions(): ReadonlyMap<string, ViewDefinition> {
    return this.registry.definitions;
  }

  /** See `ViewEngine.startingWord`. */
  startingWord(key: string): string | undefined {
    return this.text.start?.(key);
  }

  /** See `ViewEngine.setText`. */
  setText(text: TextResolver | undefined, language = ''): void {
    if (!this.text.set(text) || !text) return;
    const findings = [...this.definitions.values()].flatMap(definition =>
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

  /** See `ViewEngine.checkBinding`. */
  checkBinding(definitionId: string): boolean {
    if (this.definitions.has(definitionId)) return true;
    if (!this.unbound.has(definitionId)) {
      this.unbound.add(definitionId);
      this.report(
        issue(
          'binding.definition.unknown',
          [],
          { id: definitionId },
          'warning',
        ),
        definitionId,
      );
    }
    return false;
  }

  /**
   * A finding with nobody to reject (`onIssue`), with the resource it is
   * about where there is one.
   */
  report(found: Issue, resource?: string): void {
    this.reporter?.(found, resource);
  }

  /** See `ViewEngine.register`: the resource, without the boards asked again. */
  register(resource: ViewResource): void {
    const { definition, source, wording } = resource;
    if (source && definition.kind === 'data')
      this.sources.set(definition.source, recordSource(source));
    if (wording) this.wordings.set(definition.id, wording);
    else this.wordings.delete(definition.id);
    this.registry.register(definition);
  }

  /** See `ViewEngine.wordingOf`. */
  wordingOf(definitionId: string): DatasetWordingEditor | undefined {
    return this.wordings.get(definitionId);
  }

  /**
   * See `ViewEngine.unregister`. A source another definition still reads
   * is kept.
   */
  unregister(definitionId: string): boolean {
    const definition = this.definitions.get(definitionId);
    if (!definition) return false;
    this.registry.unregister(definitionId);
    this.wordings.delete(definitionId);
    if (
      definition.kind === 'data' &&
      ![...this.definitions.values()].some(
        other => other.kind === 'data' && other.source === definition.source,
      )
    )
      this.sources.delete(definition.source);
    return true;
  }

  /** The source registered for a definition's `source` key. */
  resolveSource(key: string): RecordSource {
    const source = this.sources.get(key);
    if (!source)
      throw new ViewCommandError(
        issue('runtime.source.unresolved', [], { source: key }),
      );
    return source;
  }

  /** See `ViewEngine.resolveOptions`. */
  resolveOptions(key: string): OptionSource {
    const resolve = this.host.resolveOptions;
    if (!resolve)
      throw new ViewCommandError(
        issue('runtime.options.unresolved', [], { source: key }),
      );
    return resolve(key);
  }
}
