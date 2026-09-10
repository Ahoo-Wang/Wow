/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { join } from 'node:path';
import ts from 'typescript';
/** Reuse the exact JSON seed from the frontend example without duplicating its schema. */
export async function loadOrderFixture() {
  const packageRoot = fileURLToPath(new URL('../../', import.meta.url));
  const temporary = mkdtempSync(join(packageRoot, '.view-service-fixture-'));
  try {
    for (const name of ['model', 'views']) {
      const source = readFileSync(
        join(packageRoot, `examples/react/sales-order/${name}.ts`),
        'utf8',
      );
      writeFileSync(
        join(temporary, `${name}.js`),
        ts.transpileModule(source, {
          compilerOptions: {
            target: ts.ScriptTarget.ES2022,
            module: ts.ModuleKind.ESNext,
          },
        }).outputText,
      );
    }
    const fixture = await import(
      pathToFileURL(join(temporary, 'views.js')).href
    );
    return {
      definition: fixture.orderDefinition,
      instances: fixture.createProtocolViews(),
    };
  } finally {
    rmSync(temporary, { recursive: true, force: true });
  }
}
