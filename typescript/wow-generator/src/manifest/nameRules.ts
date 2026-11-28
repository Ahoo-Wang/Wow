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

import { GeneratorError } from '../api/errors';
import type { ManifestDocument } from './document';

/**
 * The naming rules of a Manifest, as the Kotlin `NameRules`
 * (`api/api/src/main/kotlin/me/ahoo/wow/api/naming/NameRules.kt`) defines
 * them for the compiler, the kernel and the Kotlin contract generator: the
 * cases of `test/fixtures/name-rules.json`, which a test of each side reads,
 * hold the two equal.
 *
 * The rules are ASCII, and narrower than "an identifier can be taken from
 * it": a name is also a segment of a topic, of a URL path and of a `Wow-Wait`
 * hop, and here of a directory of the generated client.
 */

/**
 * What a name matches: an aggregate's, a command's, an event's, a processor's
 * or a handler's. A letter, then letters, digits, `_` and `-`; never a dot,
 * which joins the names (`context.aggregate.Command`, `processor.handler`).
 */
export const NAME_PATTERN = '[A-Za-z][A-Za-z0-9_-]*';

/** What a segment of an error code matches; a code is its segments joined by dots. */
export const CODE_SEGMENT_PATTERN = '[A-Za-z0-9][A-Za-z0-9_-]*';

/** What a name of the framework's own matches: `wow`, then names after dots. */
export const FRAMEWORK_NAME_PATTERN = `wow(?:\\.${NAME_PATTERN})+`;

const NAME = new RegExp(`^${NAME_PATTERN}$`);
const CODE_SEGMENT = new RegExp(`^${CODE_SEGMENT_PATTERN}$`);
const FRAMEWORK_NAME = new RegExp(`^${FRAMEWORK_NAME_PATTERN}$`);

/** Whether `name` is a name. */
export function isName(name: string): boolean {
  return NAME.test(name);
}

/** Whether `code` is an error code. */
export function isCode(code: string): boolean {
  return code.split('.').every(segment => CODE_SEGMENT.test(segment));
}

/** Whether `name` is a name of the framework's own (`wow.lifecycle.DeleteAggregate`). */
export function isFrameworkName(name: string): boolean {
  return FRAMEWORK_NAME.test(name);
}

const NAME_RULE = `a name starts with a letter and holds only letters, digits, '_' and '-' (${NAME_PATTERN}), such as order-item; a dot separates a context, an aggregate and a type, and the generated contracts take an identifier from every name`;

const CODE_RULE = `a code is segments joined by dots, each starting with a letter or a digit and holding only letters, digits, '_' and '-' (${CODE_SEGMENT_PATTERN}), such as shop.cart.out-of-stock`;

/** What is wrong with `code`, which is no code: the segment at fault. */
function codeFault(code: string): string {
  const segments = code.split('.');
  const at = segments.findIndex(segment => !CODE_SEGMENT.test(segment));
  if (segments[at] !== '') return `its segment "${segments[at]}" is not one`;
  if (segments.length === 1) return 'it is empty';
  if (at === segments.length - 1)
    return 'its last segment is empty (it ends in a dot)';
  if (at === 0) return 'its first segment is empty (it starts with a dot)';
  return `its segment ${at + 1} is empty (two dots in a row)`;
}

/** What every refusal ends with: the compiler never writes such a Manifest. */
const NOT_COMPILED =
  'The Wow compiler refuses it, so this Manifest did not come from one: correct what wrote it.';

function refuseName(entry: string): never {
  throw new GeneratorError(
    'specification',
    `${entry} is not a name: ${NAME_RULE}. ${NOT_COMPILED}`,
  );
}

/**
 * Refuses a Manifest that holds a name the Wow compiler would refuse, naming
 * the entry: an aggregate's, a command's or an event's (one of the
 * framework's own apart), each part of a subscription's (`processor.handler`),
 * or an error code. Nothing is generated from such a name: no identifier
 * guessed for it, and no directory named after it.
 *
 * @param manifest - The Manifest
 * @throws GeneratorError of kind `specification` naming the entry and the rule
 */
export function checkNames(manifest: ManifestDocument): void {
  for (const context of manifest.contexts) {
    const of = `of the context ${context.name}`;
    const typeName = (kind: string, name: string, owner: string) => {
      if (!isName(name) && !isFrameworkName(name)) {
        refuseName(`The ${kind} ${JSON.stringify(name)} ${owner}`);
      }
    };
    for (const aggregate of context.aggregates ?? []) {
      if (!isName(aggregate.name)) {
        refuseName(`The aggregate ${JSON.stringify(aggregate.name)} ${of}`);
      }
      const owner = `of the aggregate ${aggregate.name} ${of}`;
      for (const command of aggregate.commands ?? []) {
        typeName('command', command.name, owner);
      }
      for (const event of aggregate.events ?? []) {
        typeName('event', event.name, owner);
      }
    }
    for (const command of context.commands ?? []) {
      typeName('command', command.name, of);
    }
    for (const subscription of context.subscriptions ?? []) {
      if (!subscription.name.split('.').every(isName)) {
        refuseName(
          `The subscription ${JSON.stringify(subscription.name)} ${of}, a processor's name and a handler's joined by a dot,`,
        );
      }
    }
    for (const error of context.errors ?? []) {
      if (!isCode(error.code)) {
        throw new GeneratorError(
          'specification',
          `The error code ${JSON.stringify(error.code)} ${of} is not a code: ${codeFault(error.code)}; ${CODE_RULE}. ${NOT_COMPILED}`,
        );
      }
    }
  }
}
