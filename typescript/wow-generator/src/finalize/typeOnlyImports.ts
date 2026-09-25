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
  ImportDeclaration,
  ImportSpecifier,
  SourceFile,
  ts,
} from 'ts-morph';
import { Node, SyntaxKind } from 'ts-morph';

/**
 * Whether an identifier sits in a position TypeScript erases: a type
 * annotation, a type argument, an `implements` clause, an interface's
 * `extends` clause or a `typeof` type query. A class's `extends` clause, a
 * decorator, an initializer or an `export { … }` is a value position.
 *
 * This is the classification `@typescript-eslint/consistent-type-imports`
 * applies, so the generated imports satisfy that rule.
 */
function isTypePosition(identifier: Node): boolean {
  let child: Node = identifier;
  for (
    let parent = child.getParent();
    parent;
    child = parent, parent = parent.getParent()
  ) {
    // `Namespace.Type` and `namespace.value`: the reference is the leftmost name.
    if (Node.isQualifiedName(parent)) {
      if (parent.getLeft() !== child) return false;
      continue;
    }
    if (Node.isPropertyAccessExpression(parent)) {
      if (parent.getExpression() !== child) return false;
      continue;
    }
    if (Node.isExpressionWithTypeArguments(parent)) {
      const clause = parent.getParent();
      const owner = clause?.getParent();
      return !(
        Node.isHeritageClause(clause) &&
        clause.getToken() === SyntaxKind.ExtendsKeyword &&
        (Node.isClassDeclaration(owner) || Node.isClassExpression(owner))
      );
    }
    return Node.isTypeNode(parent);
  }
  return false;
}

/**
 * Plans the type-only form of every named import in a source file. Planning
 * reads the type checker; applying only edits text, so every file can be
 * planned against one program before any file changes.
 */
function planTypeOnlyImports(sourceFile: SourceFile): (() => void)[] {
  const declarations = sourceFile
    .getImportDeclarations()
    .filter(
      declaration =>
        !declaration.getDefaultImport() &&
        !declaration.getNamespaceImport() &&
        declaration.getNamedImports().length > 0,
    );
  if (declarations.length === 0) return [];

  const localNames = new Set(
    declarations.flatMap(declaration =>
      declaration
        .getNamedImports()
        .map(specifier =>
          (specifier.getAliasNode() ?? specifier.getNameNode()).getText(),
        ),
    ),
  );
  const valueSymbols = new Set<ts.Symbol>();
  sourceFile.forEachDescendant((node, traversal) => {
    if (Node.isImportDeclaration(node)) {
      traversal.skip();
      return;
    }
    if (
      Node.isIdentifier(node) &&
      localNames.has(node.getText()) &&
      !isTypePosition(node)
    ) {
      // The checker resolves shadowing: a parameter named like an import is
      // a different symbol from the import's alias.
      const symbol = node.getSymbol();
      if (symbol) valueSymbols.add(symbol.compilerSymbol);
    }
  });

  return declarations.map(declaration =>
    applyTypeOnly(declaration, specifier => {
      const symbol = specifier.getSymbol();
      // Without a symbol the use cannot be proven type-only; keep it a value.
      return !!symbol && !valueSymbols.has(symbol.compilerSymbol);
    }),
  );
}

function applyTypeOnly(
  declaration: ImportDeclaration,
  isTypeOnly: (specifier: ImportSpecifier) => boolean,
): () => void {
  const specifiers = declaration.getNamedImports();
  const typeOnly = specifiers.map(isTypeOnly);
  const allTypeOnly = typeOnly.every(Boolean);
  return () => {
    specifiers.forEach((specifier, index) => {
      const inline = !allTypeOnly && typeOnly[index];
      if (specifier.isTypeOnly() !== inline) specifier.setIsTypeOnly(inline);
    });
    if (declaration.isTypeOnly() !== allTypeOnly) {
      declaration.setIsTypeOnly(allTypeOnly);
    }
  };
}

/**
 * Rewrites named imports by how each file uses them, as
 * `@typescript-eslint/consistent-type-imports` expects:
 *
 * - every specifier used only as a type: `import type { A, B } from '…'`;
 * - values and types mixed: `import { type A, b } from '…'`;
 * - values only: `import { a, b } from '…'`.
 *
 * Usage alone decides, so an existing `type` modifier that a value use
 * contradicts is removed. Default and namespace imports are left as they are.
 */
export function applyTypeOnlyImports(sourceFiles: readonly SourceFile[]): void {
  const plans = sourceFiles.flatMap(planTypeOnlyImports);
  for (const apply of plans) apply();
}
