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

import type { Components, Reference, Schema } from '@ahoo-wang/fetcher-openapi';
import type {
  EnumDeclarationStructure,
  InterfaceDeclarationStructure,
  JSDocableNodeStructure,
  OptionalKind,
  PropertySignatureStructure,
  TypeAliasDeclarationStructure,
} from 'ts-morph';
import { StructureKind, VariableDeclarationKind } from 'ts-morph';
import type { ModelDeclaration } from '../analysis/model';
import {
  resolveModelInfo,
  resolveReferenceModelInfo,
} from '../analysis/modelInfo';
import { modelModuleSpecifier } from '../emit/imports';
import { addMainSchemaJSDoc, addSchemaJSDoc } from '../emit/jsdoc';
import type { ModuleBuilder } from '../emit/moduleBuilder';
import { indexSignatureMember } from '../emit/moduleBuilder';
import { enumMemberKey, resolvePropertyName } from '../naming/naming';
import { isReference } from '../openapi/references';
import type {
  ArraySchema,
  CompositionSchema,
  EnumSchema,
  MapSchema,
  ObjectSchema,
} from '../openapi/schemas';
import {
  getEnumText,
  getMapKeySchema,
  isArray,
  isComposition,
  isEnum,
  isMap,
  isObject,
  isReadOnly,
  toArrayType,
} from '../openapi/schemas';
import type {
  ResolvedType,
  TypeContext,
  TypeScope,
} from '../types/typeResolver';
import {
  createTypeContext,
  requiresAdditionalPropertiesIntersection,
  resolveAdditionalProperties,
  resolveAdditionalPropertyType,
  resolveLiteral,
  resolveMapValueType,
  resolveRequiredAdditionalPropertyType,
  resolveType,
} from '../types/typeResolver';
import type { EmitTarget } from './target';

/**
 * The type context of a document: its components, named the way the
 * generator names models. Build it once per generation and share it, so the
 * model names of each path are read once.
 *
 * @param components - The document's components
 */
export function documentTypeContext(components?: Components): TypeContext {
  return createTypeContext(components, {
    ofKey: key => resolveModelInfo(key),
    ofReference: resolveReferenceModelInfo,
  });
}

/**
 * Writes a model into the `types.ts` of its package: an interface, an enum or
 * a type alias, with its doc comment, and imports the models it uses.
 *
 * @param declaration - The model
 * @param target - Where it is written
 */
export function emitModel(
  declaration: ModelDeclaration,
  target: EmitTarget,
): void {
  const module = target.modules.module(declaration.file);
  if (declaration.emptyMessageBody) {
    const alias = module.add<TypeAliasDeclarationStructure>({
      kind: StructureKind.TypeAlias,
      name: declaration.info.name,
      type: 'globalThis.Record<string, never>',
      isExported: true,
    });
    addMainSchemaJSDoc(
      alias,
      declaration.docSchema,
      declaration.key,
      target.schemaDocs === 'full',
    );
    return;
  }
  new ModelEmitter(declaration, module, target).write();
}

/**
 * Names the members of an enum. Each value takes its UPPER_SNAKE_CASE form;
 * when another value already took that form - `in-progress`, `IN_PROGRESS`
 * and `inProgress` all give `IN_PROGRESS` - it takes its own value as a
 * quoted member name, or else a numbered form.
 *
 * @param values - The distinct values, in document order
 * @returns The member name of each value, quoted where it has to be
 */
export function uniqueEnumMemberNames(
  values: readonly string[],
): Map<string, string> {
  const used = new Set<string>();
  const names = new Map<string, string>();
  for (const value of values) {
    const preferred = enumMemberKey(value);
    const candidates = [preferred];
    // A numeric name cannot name an enum member, even quoted.
    if (!/^\d/.test(value)) candidates.push(value);
    let key = candidates.find(candidate => !used.has(candidate));
    for (let index = 2; key === undefined; index++) {
      if (!used.has(`${preferred}_${index}`)) key = `${preferred}_${index}`;
    }
    used.add(key);
    names.set(value, resolvePropertyName(key));
  }
  return names;
}

/** The model a {@link ModelEmitter} writes. */
export type ModelSource = Pick<
  ModelDeclaration,
  'key' | 'info' | 'schema' | 'docSchema'
>;

/**
 * Writes one model into a module: the declaration its schema calls for,
 * with the model's doc comment.
 */
export class ModelEmitter {
  private readonly modelInfo: ModelSource['info'];
  private readonly components?: Components;

  /**
   * @param model - The model
   * @param module - The module the model is written into, which also
   * receives its imports
   * @param target - The output directory, the document's type context and
   * how much of the schema the doc comment carries
   */
  constructor(
    private readonly model: ModelSource,
    readonly module: ModuleBuilder,
    private readonly target: Pick<
      EmitTarget,
      'outputDir' | 'types' | 'schemaDocs'
    >,
  ) {
    this.modelInfo = model.info;
    this.components = target.types.components;
    this.scope = {
      context: target.types,
      owner: model.info,
      specifierOf: info => modelModuleSpecifier(module, target.outputDir, info),
      imports: module.imports,
    };
  }

  /** Writes the model's declaration and its doc comment. */
  write(): void {
    const node = this.process();
    if (node) {
      addMainSchemaJSDoc(
        node,
        this.model.docSchema,
        this.model.key,
        this.target.schemaDocs === 'full',
      );
    }
  }

  private process(): JSDocableNodeStructure | undefined {
    const { schema } = this.model;
    if (isReference(schema)) {
      return this.processTypeAlias(schema);
    }
    if (isEnum(schema)) {
      return this.processEnum(schema);
    }
    if (schema.const !== undefined) {
      return this.processTypeAlias(schema);
    }
    if (
      (schema.nullable && schema.type) ||
      (isComposition(schema) &&
        (schema.type || schema.properties || schema.required))
    ) {
      return this.processTypeAlias(schema);
    }
    if (isObject(schema)) {
      return this.processInterface(schema);
    }
    if (
      isMap(schema) &&
      typeof schema.additionalProperties === 'object' &&
      !getMapKeySchema(schema) &&
      !schema.required?.length
    ) {
      return this.processIndexSignature(schema);
    }
    if (isArray(schema)) {
      return this.processArray(schema);
    }
    if (isComposition(schema)) {
      return this.processComposition(schema);
    }
    return this.processTypeAlias(schema);
  }

  /** Where the types of this model are written. */
  private readonly scope: TypeScope;

  /** Applies the imports a resolved type needs to the module. */
  private emit(resolved: ResolvedType): string {
    this.module.imports.apply(resolved.imports);
    return resolved.text;
  }

  /** The type a schema generates in this model's module, its imports added. */
  resolveType(schema: Schema | Reference): string {
    return this.emit(resolveType(schema, this.scope));
  }

  private processEnum(schema: EnumSchema): JSDocableNodeStructure | undefined {
    const enumText = getEnumText(schema);
    if (enumText) {
      const textNames = uniqueEnumMemberNames(Object.keys(enumText));
      this.module.add<EnumDeclarationStructure>({
        kind: StructureKind.Enum,
        name: this.modelInfo.name + 'EnumText',
        isExported: true,
        members: [...textNames].map(([name, memberName]) => {
          return {
            name: memberName,
            initializer: resolveLiteral(enumText[name]),
          };
        }),
      });
    }
    const stringValues = [
      ...new Set(
        schema.enum.filter(
          (value): value is string => typeof value === 'string',
        ),
      ),
    ];
    const memberNames = uniqueEnumMemberNames(stringValues);
    if (
      isComposition(schema) ||
      schema.const !== undefined ||
      (schema.type !== undefined && schema.type !== 'string') ||
      schema.enum.some(value => typeof value !== 'string')
    ) {
      if (stringValues.length) {
        this.module.add({
          kind: StructureKind.VariableStatement,
          declarationKind: VariableDeclarationKind.Const,
          isExported: true,
          declarations: [
            {
              name: this.modelInfo.name,
              initializer: writer => {
                writer.inlineBlock(() => {
                  for (const value of stringValues) {
                    writer
                      .write(`${memberNames.get(value)}: `)
                      .quote(value)
                      .write(',')
                      .newLine();
                  }
                });
                writer.write(' as const');
              },
            },
          ],
        });
      }
      return this.processTypeAlias(schema);
    }
    return this.module.add<EnumDeclarationStructure>({
      kind: StructureKind.Enum,
      name: this.modelInfo.name,
      isExported: true,
      members: stringValues.map(value => ({
        name: memberNames.get(value)!,
        initializer: resolveLiteral(value),
      })),
    });
  }

  /**
   * Adds a declared property. Property names are distinct, and
   * `resolvePropertyName` keeps them distinct: a name is kept or quoted.
   */
  private addPropertyToInterface(
    interfaceDeclaration: InterfaceDeclarationStructure,
    propName: string,
    propSchema: Schema | Reference,
  ): void {
    const propertySignature: OptionalKind<PropertySignatureStructure> = {
      name: resolvePropertyName(propName),
      type: this.resolveType(propSchema),
      isReadonly: isReadOnly(propSchema),
    };
    (interfaceDeclaration.properties ??= []).push(propertySignature);
    addSchemaJSDoc(propertySignature, propSchema);
  }

  private processInterface(
    schema: ObjectSchema,
  ): JSDocableNodeStructure | undefined {
    if (requiresAdditionalPropertiesIntersection(schema, this.components)) {
      return this.processTypeAlias(schema);
    }
    const properties: OptionalKind<PropertySignatureStructure>[] = [];
    const interfaceDeclaration = this.module.add<InterfaceDeclarationStructure>(
      {
        kind: StructureKind.Interface,
        name: this.modelInfo.name,
        isExported: true,
        properties,
      },
    );

    const schemaProperties = schema.properties || {};

    Object.entries(schemaProperties).forEach(([propName, propSchema]) => {
      this.addPropertyToInterface(interfaceDeclaration, propName, propSchema);
    });

    for (const name of schema.required ?? []) {
      if (!Object.hasOwn(schemaProperties, name)) {
        properties.push({
          name: resolvePropertyName(name),
          type: this.emit(
            resolveRequiredAdditionalPropertyType(schema, this.scope),
          ),
        });
      }
    }

    if (this.emit(resolveAdditionalProperties(schema, this.scope))) {
      properties.push(
        indexSignatureMember(
          this.emit(resolveAdditionalPropertyType(schema, this.scope)),
          ['Additional properties'],
        ),
      );
    }
    return interfaceDeclaration;
  }

  private processArray(
    schema: ArraySchema,
  ): JSDocableNodeStructure | undefined {
    const itemType = this.resolveType(schema.items);
    return this.module.add<TypeAliasDeclarationStructure>({
      kind: StructureKind.TypeAlias,
      name: this.modelInfo.name,
      type: toArrayType(itemType),
      isExported: true,
    });
  }

  /**
   * A string-keyed map is an interface with an index signature rather than an
   * alias of `Record`: an alias may not reference itself through `Record`
   * (TS2456), and a map of its own type - a tree of dictionaries - does.
   */
  private processIndexSignature(
    schema: MapSchema,
  ): JSDocableNodeStructure | undefined {
    return this.module.add<InterfaceDeclarationStructure>({
      kind: StructureKind.Interface,
      name: this.modelInfo.name,
      isExported: true,
      properties: [
        indexSignatureMember(
          this.emit(resolveMapValueType(schema, this.scope)),
        ),
      ],
    });
  }

  private processComposition(
    schema: CompositionSchema,
  ): JSDocableNodeStructure | undefined {
    return this.module.add<TypeAliasDeclarationStructure>({
      kind: StructureKind.TypeAlias,
      name: this.modelInfo.name,
      type: this.resolveType(schema),
      isExported: true,
    });
  }

  private processTypeAlias(
    schema: Schema | Reference,
  ): JSDocableNodeStructure | undefined {
    return this.module.add<TypeAliasDeclarationStructure>({
      kind: StructureKind.TypeAlias,
      name: this.modelInfo.name,
      type: this.resolveType(schema),
      isExported: true,
    });
  }
}
