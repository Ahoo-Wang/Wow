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
 * What each source admits, as one engine holds it (capabilities.md 3, 6):
 * the descriptor cache, the definitions narrowed to it, the limits it says,
 * and when to check it again.
 */

import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
import type {
  DataViewDefinition,
  Issue,
  RuntimeLimits,
  ViewDefinition,
  ViewInstance,
} from '../model/index.js';
import { issue, type FieldKindRegistry } from '../filter/index.js';
import { isOwnedPanel, panelsOf } from '../dashboard/index.js';
import {
  DescriptorCache,
  narrowDefinition,
  sourceLimits,
  type Describe,
  type NarrowedDefinition,
} from '../capabilities/index.js';
import type { RuntimeEnvironment } from './environment.js';
import type { ViewSource } from './source.js';
import { ViewCommandError } from './write.js';

/** What the capabilities read from the engine that owns them. */
export interface CapabilityHost {
  readonly kinds: FieldKindRegistry;
  /** The host's own limits, as it gave them; the source budgets may be left out. */
  readonly limits: Partial<RuntimeLimits> | undefined;
  readonly environment: RuntimeEnvironment;
  resolveSource(key: string): ViewSource;
  /** A definition as the application declared it, by id. */
  declared(id: string): ViewDefinition | undefined;
  /** A finding with nobody to reject: `onIssue`. */
  report(found: Issue): void;
}

/** A definition and the limits a runtime over it runs under. */
export interface EffectiveDefinition {
  definition: DataViewDefinition;
  limits: RuntimeLimits;
}

export class SourceCapabilities {
  /**
   * The limits of a source with no descriptor: the host's over the
   * defaults, as every source ran under before descriptors.
   */
  readonly fallback: RuntimeLimits;
  private readonly host: CapabilityHost;
  private readonly cache: DescriptorCache;
  /** Each definition narrowed to the version it was narrowed against. */
  private readonly narrowed = new Map<
    string,
    { version: string; result: NarrowedDefinition }
  >();
  private readonly limitsBySource = new Map<
    string,
    { version: string; limits: RuntimeLimits }
  >();
  private readonly stopWatching: () => void;

  constructor(host: CapabilityHost) {
    this.host = host;
    this.fallback = sourceLimits(host.limits, null);
    this.cache = new DescriptorCache({
      now: () => host.environment.now().getTime(),
      // Nobody is shown this: the views run on the definition as they did
      // before descriptors, and only a query the source really refuses says
      // anything on screen (capabilities.md 6「取不到时」).
      failed: source =>
        host.report(
          issue('capability.descriptor.unavailable', [], { source }, 'note'),
        ),
    });
    // Coming back to the page is one of the moments to check again; the
    // cache skips a descriptor younger than its maximum age.
    const visibility = host.environment.visibility;
    this.stopWatching = visibility.subscribe(() => {
      if (visibility.isVisible()) this.cache.revalidateStale();
    });
  }

  /**
   * Reads the descriptor of every source the instance's view runs on, the
   * first time a view over it opens, so its first query already goes out
   * narrowed; later openings answer at once and check a stale descriptor
   * in the background. A board's saved views are read as each panel
   * resolves (`prepare`); the views it owns are read here.
   */
  async prepareFor(instance: ViewInstance): Promise<void> {
    const definition = this.host.declared(instance.definitionId);
    if (!definition) return;
    if (definition.kind === 'data') return this.prepare(definition);
    const config = instance.config;
    if (config.kind !== 'dashboard') return;
    const owned = panelsOf(config)
      .filter(isOwnedPanel)
      .map(panel => this.host.declared(panel.owned.definitionId))
      .filter(entry => entry !== undefined);
    await Promise.all(owned.map(entry => this.prepare(entry)));
  }

  /** Reads one definition's source descriptor; see `prepareFor`. */
  async prepare(definition: ViewDefinition): Promise<void> {
    if (definition.kind !== 'data') return;
    const describe = this.describer(definition.source);
    if (describe) await this.cache.load(definition.source, describe);
  }

  /**
   * Starts reading a source's descriptor without waiting, for a view made
   * from nothing (`create`), which cannot wait: it runs on what is held,
   * and the next view over the source on what this read brings.
   */
  warm(definition: ViewDefinition): void {
    void this.prepare(definition);
  }

  /** Checks a source's descriptor again when it is stale: a press of refresh. */
  revalidate(source: string): void {
    void this.cache.revalidate(source);
  }

  /**
   * The definition a runtime over it runs on, and the limits: narrowed to
   * the descriptor held for its source, or as declared while there is
   * none. Refused, as a definition failing admission is, when the
   * descriptor contradicts it (capabilities.md 4.6).
   */
  effective(definition: DataViewDefinition): EffectiveDefinition {
    const descriptor = this.cache.current(definition.source);
    if (!descriptor) return { definition, limits: this.fallback };
    const { definition: narrowed, findings } = this.narrow(
      definition.id,
      definition,
      descriptor,
    );
    const errors = findings.filter(found => found.severity === 'error');
    if (errors.length > 0)
      throw new ViewCommandError(
        issue('view.definition.invalid', [], {
          id: definition.id,
          issues: errors.length,
        }),
      );
    return {
      definition: narrowed,
      limits: this.limitsOf(definition.source, descriptor),
    };
  }

  dispose(): void {
    this.stopWatching();
  }

  /**
   * How to read a source's descriptor, or `null` without one. A key the
   * host cannot resolve is left to fail where it always has — when a view
   * over it is built — so a board's other panels are not taken down with it.
   */
  private describer(source: string): Describe | null {
    let resolved: ViewSource;
    try {
      resolved = this.host.resolveSource(source);
    } catch {
      return null;
    }
    if (!resolved.describe) return null;
    return previous => resolved.describe!(previous);
  }

  /**
   * Narrowed once per descriptor version, and what it found reported once
   * with it (capabilities.md 4.6). The definition narrowed is the declared
   * one, whatever copy the caller holds — a panel's may already be
   * narrowed against an older version.
   */
  private narrow(
    id: string,
    given: DataViewDefinition,
    descriptor: QueryModelDescriptor,
  ): NarrowedDefinition {
    const held = this.narrowed.get(id);
    if (held?.version === descriptor.version) return held.result;
    const declared = this.host.declared(id);
    const definition = declared?.kind === 'data' ? declared : given;
    const result = narrowDefinition(definition, descriptor, this.host.kinds);
    this.narrowed.set(id, { version: descriptor.version, result });
    for (const found of result.findings)
      this.host.report({
        ...found,
        params: { ...found.params, definition: id },
      });
    return result;
  }

  private limitsOf(
    source: string,
    descriptor: QueryModelDescriptor,
  ): RuntimeLimits {
    const held = this.limitsBySource.get(source);
    if (held?.version === descriptor.version) return held.limits;
    const limits = sourceLimits(this.host.limits, descriptor);
    this.limitsBySource.set(source, { version: descriptor.version, limits });
    return limits;
  }
}
