import { defineConfig } from 'vite';
import dts from 'unplugin-dts/vite';
import { resolve } from 'path';

export default defineConfig({
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
      name: 'FetcherGenerator',
    },
    rollupOptions: {
      external: [
        '@ahoo-wang/fetcher',
        '@ahoo-wang/fetcher-eventstream',
        '@ahoo-wang/fetcher-decorator',
        '@ahoo-wang/fetcher-openapi',
        '@ahoo-wang/fetcher-wow',
        'commander',
        'ts-morph',
        'yaml',
        'fs',
        'path',
      ],
      output: {
        globals: {
          '@ahoo-wang/fetcher': 'Fetcher',
          '@ahoo-wang/fetcher-eventstream': 'FetcherEventStream',
          '@ahoo-wang/fetcher-decorator': 'FetcherDecorator',
          '@ahoo-wang/fetcher-openapi': 'FetcherOpenAPI',
          '@ahoo-wang/fetcher-wow': 'FetcherWow',
          'commander': 'Commander',
          'ts-morph': 'ts-morph',
          'yaml': 'yaml',
          'fs': 'fs',
          'path': 'path'
        }
      }
    },
  },
  plugins: [
    dts({
      entryRoot: 'src',
      outDirs: ['dist'],
      tsconfigPath: 'tsconfig.json',
    }),
  ],
});
