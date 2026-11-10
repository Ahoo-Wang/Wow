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
import { isObject, type JsonSchema, schemaKey, typesOf } from './document';

/** The number formats a client reads and writes as their exact text: a `string` in the generated types. */
export const TEXT_NUMBER_FORMATS: ReadonlySet<string> = new Set([
  'int64',
  'bigint',
  'decimal',
]);

/**
 * The exact numbers of a value, as `@ahoo-wang/wow-client`'s `TextNumbers`
 * holds them:
 *
 * - `true`: the value is such a number;
 * - `members`: its members' marks by name, `'*'` for every item or map value;
 * - `lazy`: the marks of a recursive type, the constant that holds them;
 * - `or`: an exact number, or an object or array marked `or`;
 * - `by`: an object's marks chosen by the string value of its member `by`.
 */
export type TextNumbersModel =
  | true
  | { readonly members: Readonly<Record<string, TextNumbersModel>> }
  | { readonly lazy: TextNumbersConstant }
  | { readonly or: TextNumbersModel }
  | {
      readonly by: string;
      readonly cases: Readonly<Record<string, TextNumbersModel>>;
    };

/** Where a recursive type's marks are declared: a constant of its context's `textNumbers.ts`. */
export interface TextNumbersConstant {
  readonly name: string;
  readonly file: string;
}

/** A declared recursive type's marks. */
export interface TextNumbersDeclaration extends TextNumbersConstant {
  /** The type's `$defs` key. */
  readonly key: string;
  /** It is the type with what an `allOf` says beside it, not the type alone. */
  readonly beside?: true;
  readonly marks: TextNumbersModel;
}

/** A number a schema admits: its format, and whether it is read exactly. */
interface NumberShape {
  readonly exact: boolean;
  readonly format: string;
}

/**
 * What a schema says of its numbers, plain ones included so that two
 * alternatives can be found to disagree: its own number, its members' (`'*'`
 * for items and map values), the cases a member chooses, or a reference to a
 * type whose shape is still being read (recursion).
 */
interface Shape {
  readonly number?: NumberShape;
  readonly members?: Readonly<Record<string, Shape>>;
  readonly by?: {
    readonly name: string;
    readonly cases: Record<string, Shape>;
  };
  readonly ref?: string;
  /** What else is said of a {@link ref}'s value, beside its type (`allOf`). */
  readonly beside?: Shape;
}

/** Two alternatives that read what is at `path` differently, as `detail` says. */
class Disagreement {
  constructor(
    readonly path: string,
    readonly detail: string,
  ) {}
}

/**
 * Derives the exact numbers of a Manifest's schemas: each `integer` or
 * `number` of a {@link TEXT_NUMBER_FORMATS} format, through objects, arrays,
 * maps, references and their recursion, and the alternatives of a union,
 * kept apart by their JSON kind or by a member with a constant value. A
 * union whose alternatives read one number differently, with no such member,
 * is a specification error: no reader could tell which applies.
 */
export class TextNumbers {
  private readonly shapes = new Map<string, Shape | undefined>();
  private readonly reading = new Set<string>();
  private readonly lazy = new Map<string, TextNumbersConstant>();
  /**
   * A recursive type with what an `allOf` says beside it, by an id of both:
   * its type's key, and its own shape. It recurs as its type does, so it is
   * declared as a constant of its own.
   */
  private readonly variants = new Map<
    string,
    { readonly key: string; readonly shape: Shape | undefined }
  >();

  /**
   * @param defs - The Manifest's `$defs`
   * @param constantOf - Where the marks of the recursive type `key` are declared
   */
  constructor(
    private readonly defs: Readonly<Record<string, JsonSchema>>,
    private readonly constantOf: (key: string) => TextNumbersConstant,
  ) {}

  /**
   * The marks of `schema`, or undefined when it has no exact number.
   *
   * @param where - What the schema is, for an error
   * @throws GeneratorError of kind `specification` for a union no member tells apart
   */
  marks(schema: unknown, where: string): TextNumbersModel | undefined {
    return this.toMarks(this.shape(schema, where));
  }

  /**
   * The marks of every recursive type a mark refers to, in the order of their
   * names, each declared once.
   */
  declarations(): TextNumbersDeclaration[] {
    const declared = new Map<string, TextNumbersDeclaration>();
    // A declaration's marks may refer to more recursive types.
    while (declared.size < this.lazy.size) {
      for (const [id, constant] of [...this.lazy]) {
        if (declared.has(id)) continue;
        const variant = this.variants.get(id);
        declared.set(id, {
          key: variant?.key ?? id,
          ...(variant === undefined ? {} : { beside: true as const }),
          ...constant,
          marks: this.toMarks(variant?.shape ?? this.shapes.get(id))!,
        });
      }
    }
    return [...declared.values()].sort((left, right) =>
      left.name < right.name ? -1 : left.name > right.name ? 1 : 0,
    );
  }

  private shape(schema: unknown, where: string): Shape | undefined {
    if (!isObject(schema)) return undefined;
    if (typeof schema.$ref === 'string') {
      const key = schemaKey(schema.$ref);
      return key === undefined ? undefined : this.shapeOf(key);
    }
    const parts: (Shape | undefined)[] = [];
    const types = typesOf(schema);
    const numeric = types.find(type => type === 'number' || type === 'integer');
    if (numeric !== undefined) {
      const format = typeof schema.format === 'string' ? schema.format : '';
      parts.push({
        number: {
          exact: TEXT_NUMBER_FORMATS.has(format),
          format: format || numeric,
        },
      });
    }
    const members: Record<string, Shape> = {};
    if (isObject(schema.properties)) {
      for (const [name, property] of Object.entries(schema.properties)) {
        const shape = this.shape(property, `${where}.${name}`);
        if (shape !== undefined) members[name] = shape;
      }
    }
    const each = all([
      this.shape(schema.items, `${where}.*`),
      this.shape(schema.additionalProperties, `${where}.*`),
    ]);
    if (each !== undefined) members['*'] = each;
    if (Object.keys(members).length > 0) parts.push({ members });
    for (const member of ['oneOf', 'anyOf']) {
      const alternatives = schema[member];
      if (Array.isArray(alternatives)) {
        parts.push(this.either(alternatives, where));
      }
    }
    if (Array.isArray(schema.allOf)) {
      parts.push(...schema.allOf.map(part => this.shape(part, where)));
    }
    return all(parts);
  }

  /** The shape of the type `key`: a reference while it is still being read. */
  private shapeOf(key: string): Shape | undefined {
    if (this.reading.has(key)) return { ref: key };
    if (this.shapes.has(key)) return this.shapes.get(key);
    const schema = this.defs[key];
    if (!isObject(schema)) return undefined;
    this.reading.add(key);
    const shape = this.shape(schema, key);
    this.reading.delete(key);
    this.shapes.set(key, shape);
    return shape;
  }

  /** The shape of a value any one of `alternatives` describes. */
  private either(alternatives: unknown[], where: string): Shape | undefined {
    const shapes = alternatives.map(alternative =>
      this.shape(alternative, where),
    );
    try {
      return shapes.reduce<Shape | undefined>(
        (left, right) => this.merge(left, right, ''),
        undefined,
      );
    } catch (error) {
      if (!(error instanceof Disagreement)) throw error;
      const name = this.discriminant(alternatives);
      if (name === undefined) {
        throw new GeneratorError(
          'specification',
          `The alternatives of ${where} disagree at ${error.path || 'their root'}: ${error.detail}, and no member with a constant value tells them apart. Give the alternatives a discriminator (@JsonTypeInfo with @JsonSubTypes), or the same number format.`,
        );
      }
      // The alternatives that are objects go by their case; the others by their kind.
      const cases: Record<string, Shape> = {};
      let rest: Shape | undefined;
      alternatives.forEach((alternative, index) => {
        const value = this.constant(alternative, name);
        const shape = shapes[index];
        if (value !== undefined) {
          if (shape !== undefined) cases[value] = shape;
          return;
        }
        // Only its own number can go beside the cases (textNumberOr): marks
        // inside it, of items or values, have no form to be chosen by.
        if (
          shape !== undefined &&
          this.exact({ ...shape, number: undefined }, new Set())
        ) {
          throw new GeneratorError(
            'specification',
            `The alternatives of ${where} are told apart by their \`${name}\`, but alternative ${index + 1} has none and holds exact numbers no reader could choose by it. Give it a \`${name}\` too, or take it out of the union.`,
          );
        }
        rest = this.merge(rest, shape, '');
      });
      return all([
        rest?.number ? { number: rest.number } : undefined,
        { by: { name, cases } },
      ]);
    }
  }

  /**
   * The member every object among `alternatives` has a constant string value
   * of, a different one each: the discriminator Jackson writes them by.
   */
  private discriminant(alternatives: unknown[]): string | undefined {
    const objects = alternatives
      .map(alternative => this.properties(alternative, new Set()))
      .filter(properties => Object.keys(properties).length > 0);
    if (objects.length < 2) return undefined;
    return Object.keys(objects[0]!)
      .sort()
      .find(name => {
        const values = objects.map(properties => constantOf(properties[name]));
        return (
          values.every(value => value !== undefined) &&
          new Set(values).size === values.length
        );
      });
  }

  /** The constant string value of `alternative`'s member `name`, if it has one. */
  private constant(alternative: unknown, name: string): string | undefined {
    return constantOf(this.properties(alternative, new Set())[name]);
  }

  /** The properties of an object schema, through its references and `allOf` parts. */
  private properties(
    schema: unknown,
    seen: Set<string>,
  ): Record<string, unknown> {
    if (!isObject(schema)) return {};
    if (typeof schema.$ref === 'string') {
      const key = schemaKey(schema.$ref);
      if (key === undefined || seen.has(key)) return {};
      return this.properties(this.defs[key], new Set([...seen, key]));
    }
    return Object.assign(
      {},
      ...(Array.isArray(schema.allOf) ? schema.allOf : []).map(part =>
        this.properties(part, seen),
      ),
      isObject(schema.properties) ? schema.properties : {},
    );
  }

  /** The shape of a value either `left` or `right` describes; a {@link Disagreement} where they read a number differently. */
  private merge(
    left: Shape | undefined,
    right: Shape | undefined,
    path: string,
  ): Shape | undefined {
    if (left === undefined) return right;
    if (right === undefined) return left;
    if (
      left.ref !== undefined &&
      left.ref === right.ref &&
      left.beside === undefined &&
      right.beside === undefined
    ) {
      return left;
    }
    if (left.ref !== undefined || right.ref !== undefined) {
      return this.merge(this.expand(left), this.expand(right), path);
    }
    if (left.by !== undefined || right.by !== undefined) {
      throw new Disagreement(
        path,
        'one is an object told apart by a member, another is not',
      );
    }
    let number = left.number ?? right.number;
    if (
      left.number &&
      right.number &&
      left.number.exact !== right.number.exact
    ) {
      const [exact, plain] = left.number.exact
        ? [left.number, right.number]
        : [right.number, left.number];
      throw new Disagreement(
        path,
        `one is \`${exact.format}\`, read as its exact text, another \`${plain.format}\`, read as a number`,
      );
    }
    if (left.number?.exact) number = left.number;
    const members: Record<string, Shape> = { ...left.members };
    for (const [name, shape] of Object.entries(right.members ?? {})) {
      members[name] = this.merge(
        members[name],
        shape,
        path === '' ? name : `${path}.${name}`,
      )!;
    }
    return {
      ...(number === undefined ? {} : { number }),
      ...(Object.keys(members).length === 0 ? {} : { members }),
    };
  }

  /** A reference's shape where it is known; else a disagreement unless the other side reads no number. */
  private expand(shape: Shape): Shape | undefined {
    if (shape.ref === undefined) return shape;
    if (this.shapes.has(shape.ref)) {
      return all([this.shapes.get(shape.ref), shape.beside]);
    }
    throw new Disagreement(
      '',
      `one is \`${shape.ref}\`, a recursive type not yet read, another is not`,
    );
  }

  private toMarks(shape: Shape | undefined): TextNumbersModel | undefined {
    if (shape === undefined) return undefined;
    if (shape.ref !== undefined) {
      const ref = shape.ref;
      // What is said beside a recursive type holds too: the two are another
      // recursive type, a constant of its own.
      const beside = this.exact(shape.beside, new Set());
      if (!beside && !this.exact({ ref }, new Set())) return undefined;
      const id = beside ? `${ref} ${JSON.stringify(shape.beside)}` : ref;
      let constant = this.lazy.get(id);
      if (constant === undefined) {
        constant = this.constantOf(ref);
        if (beside) {
          const taken = [...this.variants.values()].filter(
            variant => variant.key === ref,
          ).length;
          constant = { ...constant, name: `${constant.name}${taken + 2}` };
          this.variants.set(id, {
            key: ref,
            shape: all([this.shapes.get(ref), shape.beside]),
          });
        }
        this.lazy.set(id, constant);
      }
      return { lazy: constant };
    }
    let inner: TextNumbersModel | undefined;
    if (shape.by !== undefined) {
      const cases = marksOf(shape.by.cases, case_ => this.toMarks(case_));
      inner = cases === undefined ? undefined : { by: shape.by.name, cases };
    } else if (shape.members !== undefined) {
      const members = marksOf(shape.members, member => this.toMarks(member));
      inner = members === undefined ? undefined : { members };
    }
    if (!shape.number?.exact) return inner;
    return inner === undefined ? true : { or: inner };
  }

  /** Whether a shape reaches an exact number, following its references once each. */
  private exact(shape: Shape | undefined, seen: Set<string>): boolean {
    if (shape === undefined) return false;
    if (shape.ref !== undefined) {
      if (this.exact(shape.beside, seen)) return true;
      if (seen.has(shape.ref)) return false;
      seen.add(shape.ref);
      return this.exact(this.shapes.get(shape.ref), seen);
    }
    return (
      shape.number?.exact === true ||
      Object.values(shape.members ?? {}).some(member =>
        this.exact(member, seen),
      ) ||
      Object.values(shape.by?.cases ?? {}).some(case_ =>
        this.exact(case_, seen),
      )
    );
  }
}

/** The shape of a value every one of `parts` describes at once (`allOf`, or a schema and its alternatives). */
function all(parts: readonly (Shape | undefined)[]): Shape | undefined {
  return parts.reduce<Shape | undefined>((left, right) => {
    if (left === undefined) return right;
    if (right === undefined) return left;
    // A reference keeps what else is said of its value beside it.
    if (left.ref !== undefined || right.ref !== undefined) {
      const [ref, other] =
        left.ref !== undefined ? [left, right] : [right, left];
      return { ref: ref.ref, beside: all([ref.beside, other])! };
    }
    const number =
      left.number?.exact || right.number === undefined
        ? (left.number ?? right.number)
        : right.number;
    const members: Record<string, Shape> = { ...left.members };
    for (const [name, shape] of Object.entries(right.members ?? {})) {
      members[name] = all([members[name], shape])!;
    }
    const by = left.by ?? right.by;
    return {
      ...(number === undefined ? {} : { number }),
      ...(by === undefined
        ? Object.keys(members).length === 0
          ? {}
          : { members }
        : {
            // Members said beside the cases hold in every case.
            by: {
              name: by.name,
              cases: Object.fromEntries(
                Object.entries(by.cases).map(([value, case_]) => [
                  value,
                  all([case_, { members }])!,
                ]),
              ),
            },
          }),
    };
  }, undefined);
}

/** The marks of each entry that marks something, or undefined when none does. */
function marksOf(
  entries: Readonly<Record<string, Shape>>,
  marks: (shape: Shape) => TextNumbersModel | undefined,
): Record<string, TextNumbersModel> | undefined {
  const marked = Object.entries(entries).flatMap(([name, shape]) => {
    const mark = marks(shape);
    return mark === undefined ? [] : [[name, mark] as const];
  });
  return marked.length === 0 ? undefined : Object.fromEntries(marked);
}

/** The constant string value a property schema allows, if it allows one. */
function constantOf(schema: unknown): string | undefined {
  if (!isObject(schema)) return undefined;
  if (typeof schema.const === 'string') return schema.const;
  return Array.isArray(schema.enum) &&
    schema.enum.length === 1 &&
    typeof schema.enum[0] === 'string'
    ? schema.enum[0]
    : undefined;
}
