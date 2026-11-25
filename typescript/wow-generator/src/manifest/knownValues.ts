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

import type {
  ManifestAggregate,
  ManifestCommand,
  ManifestContext,
  ManifestDocument,
} from './document';

/** The values of each enumeration this generator knows, as this Wow writes them. */
const KNOWN = {
  in: new Set(['path', 'query']),
  ownership: new Set(['owner', 'tenant']),
  idSource: new Set(['assigned', 'owner']),
  type: new Set(['string', 'integer', 'boolean']),
  creation: new Set(['none', 'required', 'allowed']),
} as const;

/** Whether `value`, when present, is one of `enumeration`'s. */
const known = (enumeration: keyof typeof KNOWN, value: string | undefined) =>
  value === undefined || KNOWN[enumeration].has(value);

/**
 * `manifest` with what holds a value this generator does not know left out,
 * as Kotlin's `ManifestTree.read` leaves it out (#7020 F14): each of these
 * values decides how the generated code calls the service, so it never
 * guesses one. A newer Wow may write one in a minor release.
 *
 * - A service route a parameter of which is in a location other than `path`
 *   or `query`.
 * - An aggregate of an unknown `ownership` or `idSource`, or with an access
 *   attribute of an unknown `type`.
 * - A command of an unknown `creation`.
 *
 * Each is named in a warning.
 */
export function knownValues(manifest: ManifestDocument): {
  manifest: ManifestDocument;
  warnings: string[];
} {
  const warnings: string[] = [];
  const leftOut = (what: string) =>
    warnings.push(
      `${what} is left out: a value of it is unknown to this generator; generate with a newer @ahoo-wang/wow-generator.`,
    );
  const contexts = manifest.contexts.map((context): ManifestContext => {
    const aggregates = (context.aggregates ?? []).flatMap(aggregate => {
      if (
        !known('ownership', aggregate.ownership) ||
        !known('idSource', aggregate.idSource) ||
        (aggregate.access ?? []).some(
          attribute => !known('type', attribute.type),
        )
      ) {
        leftOut(`The aggregate ${context.name}.${aggregate.name}`);
        return [];
      }
      return [withKnownCommands(context, aggregate, leftOut)];
    });
    const routes = (context.routes ?? []).filter(route => {
      if ((route.parameters ?? []).every(p => known('in', p.in))) return true;
      leftOut(`The service route ${context.name}.${route.name}`);
      return false;
    });
    return {
      ...context,
      ...(context.aggregates === undefined ? {} : { aggregates }),
      ...(context.routes === undefined ? {} : { routes }),
    };
  });
  return warnings.length === 0
    ? { manifest, warnings }
    : { manifest: { ...manifest, contexts }, warnings };
}

/** `aggregate` without its commands of an unknown `creation`. */
function withKnownCommands(
  context: ManifestContext,
  aggregate: ManifestAggregate,
  leftOut: (what: string) => void,
): ManifestAggregate {
  if (aggregate.commands === undefined) return aggregate;
  const commands = aggregate.commands.filter((command: ManifestCommand) => {
    if (known('creation', command.creation)) return true;
    leftOut(`The command ${context.name}.${aggregate.name}.${command.name}`);
    return false;
  });
  return commands.length === aggregate.commands.length
    ? aggregate
    : { ...aggregate, commands };
}
