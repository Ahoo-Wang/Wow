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
  EnumValueDescriptor,
  FieldAggregateDescriptor,
  FieldDescriptor,
  FieldSortDescriptor,
  QueryModelDescriptor,
  QuerySemanticType,
} from '@ahoo-wang/wow-client';

/**
 * What a descriptor says about one path a definition names, whether a field
 * of its own (`fields`) or a key under a dynamic pattern (`dynamic`).
 */
export interface DescribedField {
  /** The path the source knows the field by, where it was named by an alias. */
  canonical?: string;
  /**
   * The variants that have the field, by their discriminator's value, for a
   * field of an element whose fields differ by variant (#3519): an event
   * stream's payload field lives in some event types only.
   */
  variants?: string[];
  /** Every operator the path admits on its own. */
  operators: ReadonlySet<string>;
  sort: FieldSortDescriptor;
  /** Absent when the path cannot be aggregated at all. */
  aggregate?: FieldAggregateDescriptor;
  semantic?: QuerySemanticType;
  /** Whether a projection may select it; absent for a dynamic key. */
  project?: boolean;
  /**
   * `false` for a protected field whose raw value may not be compared: it
   * lists no operators and no paged sort (#3519, `PROTECTED_COMPARISON`).
   */
  comparable?: boolean;
  /** Set when the field is deprecated: still queryable, better avoided. */
  deprecated?: { message?: string };
  enum?: readonly EnumValueDescriptor[];
}

const NO_SORT: FieldSortDescriptor = { paged: false, cursor: false };

/** The segment a dynamic pattern writes in place of a map's key. */
const KEY_SEGMENT = '{key}';

/**
 * The descriptor's entry for a path, or `null` when it lists none.
 *
 * A root field (`scope` absent) is a field without a `scope` of the same
 * path, and failing that the dynamic patterns it falls under — a key the
 * pattern excludes (#3477) is a field of its own, so it never matches. The
 * service describes each pattern once (#3489), so a key matches at most one;
 * should two ever match, each admits its operators on its own, and the key
 * gets their union. A field
 * inside an element (`scope`: the array's path) is the descriptor's field of
 * that path in that scope; dynamic patterns never describe one.
 */
export function describedField(
  descriptor: QueryModelDescriptor,
  path: string,
  scope?: string,
): DescribedField | null {
  const field: FieldDescriptor | undefined =
    descriptor.fields.find(
      entry => entry.path === path && entry.scope === scope,
    ) ??
    // An alias is another name for one field (#3519): the source replaces
    // it with the path before it runs a query, and answers by the path.
    descriptor.fields.find(
      entry => entry.scope === scope && (entry.aliases ?? []).includes(path),
    );
  if (field)
    return {
      ...(field.path === path ? {} : { canonical: field.path }),
      operators: new Set(field.filter.operators),
      sort: field.sort,
      project: field.project,
      ...(field.sensitivity
        ? { comparable: field.sensitivity.comparable }
        : {}),
      ...(field.deprecated
        ? {
            deprecated:
              field.deprecated.message === undefined
                ? {}
                : { message: field.deprecated.message },
          }
        : {}),
      ...(field.aggregate ? { aggregate: field.aggregate } : {}),
      ...(field.semantic ? { semantic: field.semantic } : {}),
      ...(field.enum ? { enum: field.enum } : {}),
    };
  if (scope !== undefined) return variantField(descriptor, path, scope);

  const matched = descriptor.dynamic.filter(entry =>
    matchesPattern(entry.pattern, path, entry.excludedKeys ?? []),
  );
  if (matched.length === 0) return null;
  return {
    operators: new Set(matched.flatMap(entry => entry.filter.operators)),
    // A pattern says only how it is filtered: nothing under it sorts or
    // aggregates.
    sort: NO_SORT,
  };
}

/**
 * A field of an element whose fields differ by variant, found in the
 * variants that have it (#3519). Each variant lists it relative to the
 * element with its own types; its operators, sorts and aggregation are the
 * shared path's, so the variants agree on them and the first answers, the
 * operators taken over all of them.
 */
function variantField(
  descriptor: QueryModelDescriptor,
  path: string,
  scope: string,
): DescribedField | null {
  const variants = descriptor.variants;
  if (!variants || variants.element !== scope) return null;
  const relative = path.slice(scope.length + 1);
  const found = variants.values.flatMap(variant => {
    const field = variant.fields.find(
      entry =>
        entry.path === relative || (entry.aliases ?? []).includes(relative),
    );
    return field ? [{ value: variant.value, field }] : [];
  });
  const first = found[0]?.field;
  if (!first) return null;
  return {
    ...(first.path === relative ? {} : { canonical: `${scope}.${first.path}` }),
    operators: new Set(found.flatMap(entry => entry.field.filter.operators)),
    sort: first.sort,
    project: first.project,
    ...(first.aggregate ? { aggregate: first.aggregate } : {}),
    ...(first.semantic ? { semantic: first.semantic } : {}),
    variants: found.map(entry => entry.value),
  };
}

function matchesPattern(
  pattern: string,
  path: string,
  excluded: readonly string[],
): boolean {
  const want = pattern.split('.');
  const have = path.split('.');
  if (want.length !== have.length) return false;
  return want.every((segment, index) =>
    segment === KEY_SEGMENT
      ? !excluded.includes(have[index])
      : segment === have[index],
  );
}
