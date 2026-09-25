import { defineConfig } from 'vite';
import dts from 'unplugin-dts/vite';
import { readFileSync } from 'node:fs';
import { resolve } from 'path';

// Only the version string reaches dist: importing package.json from the source
// would bundle the whole manifest, devDependencies and `catalog:` ranges
// included. Read at build time, so `pnpm set-version` needs nothing else.
const { version } = JSON.parse(
  readFileSync(resolve(__dirname, 'package.json'), 'utf8'),
) as { version: string };

export default defineConfig({
  define: {
    __WOW_GENERATOR_VERSION__: JSON.stringify(version),
  },
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  build: {
    sourcemap: true,
    lib: {
      entry: {
        index: resolve(__dirname, 'src/index.ts'),
        cli: resolve(__dirname, 'src/cli.ts'),
      },
      formats: ['es', 'cjs'],
      name: 'WowGenerator',
    },
    rollupOptions: {
      external: [
        '@ahoo-wang/fetcher',
        '@ahoo-wang/fetcher-eventstream',
        '@ahoo-wang/fetcher-decorator',
        '@ahoo-wang/fetcher-openapi',
        '@ahoo-wang/wow-client',
        'commander',
        'ts-morph',
        'yaml',
        'fs',
        'path',
        // Node builtins written with the `node:` prefix, so one slipping into
        // a source file cannot be bundled into a broken import at runtime.
        /^node:/,
      ],
      output: {
        globals: {
          '@ahoo-wang/fetcher': 'Fetcher',
          '@ahoo-wang/fetcher-eventstream': 'FetcherEventStream',
          '@ahoo-wang/fetcher-decorator': 'FetcherDecorator',
          '@ahoo-wang/fetcher-openapi': 'FetcherOpenAPI',
          '@ahoo-wang/wow-client': 'WowClient',
          commander: 'Commander',
          'ts-morph': 'ts-morph',
          yaml: 'yaml',
          fs: 'fs',
          path: 'path',
        },
      },
    },
  },
  plugins: [
    dts({
      entryRoot: 'src',
      // `.d.cts` for the `require` condition and `.d.ts` for `import`, so
      // node16/nodenext consumers see CommonJS and ES module types for the
      // matching build. A primary out dir with a module format makes the plugin
      // resolve every relative specifier and write it with its runtime
      // extension (`./types.js`, `./aggregate/index.cjs`), whatever the source
      // wrote.
      outDirs: [{ dir: 'dist', moduleFormat: 'cjs' }, 'dist'],
      tsconfigPath: 'tsconfig.json',
    }),
  ],
});
