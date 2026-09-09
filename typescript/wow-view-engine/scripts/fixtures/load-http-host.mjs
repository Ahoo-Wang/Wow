/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { createServer } from 'vite';
import { fileURLToPath } from 'node:url';
/** Execute development TypeScript without adding it to the library build. */
export async function loadHttpHost() {
  const server = await createServer({
    configFile: false,
    root: fileURLToPath(new URL('../../../../', import.meta.url)),
    server: { middlewareMode: true },
    ssr: {
      external: ['@ahoo-wang/fetcher-view-engine', '@ahoo-wang/fetcher-wow'],
    },
    logLevel: 'error',
  });
  try {
    return await server.ssrLoadModule(
      fileURLToPath(new URL('../../dev/http/index.ts', import.meta.url)),
    );
  } finally {
    await server.close();
  }
}
