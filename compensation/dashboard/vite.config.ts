import { readFileSync } from "node:fs";
import { defineConfig } from "vite";
import tailwindcss from "@tailwindcss/vite";
import { fileURLToPath } from "node:url";

import react, { reactCompilerPreset } from "@vitejs/plugin-react";
import babel from "@rolldown/plugin-babel";
import { resolveCommitSha } from "./buildMetadata.ts";

function resolveProjectVersion(): string {
  const gradleProperties = readFileSync(
    new URL("../../gradle.properties", import.meta.url),
    "utf8",
  );
  const version = gradleProperties.match(/^version=(.+)$/m)?.[1]?.trim();
  if (!version) {
    throw new Error("Unable to resolve version from root gradle.properties.");
  }
  return version;
}

// https://vite.dev/config/
export default defineConfig({
  define: {
    "import.meta.env.VITE_APP_COMMIT_SHA": JSON.stringify(resolveCommitSha()),
    "import.meta.env.VITE_APP_VERSION": JSON.stringify(resolveProjectVersion()),
  },
  build: {
    rolldownOptions: {
      output: {
        codeSplitting: {
          groups: [
            // Keep stable framework code independently cacheable.
            {
              name: "react",
              test: /node_modules[\\/](?:react|react-dom|scheduler)[\\/]/,
              priority: 20,
            },
            {
              name: "router",
              test: /node_modules[\\/]react-router[\\/]/,
              priority: 10,
            },
          ],
        },
      },
    },
  },
  oxc: {
    decorator: {
      legacy: true,
      emitDecoratorMetadata: true,
    },
  },
  plugins: [
    react(),
    babel({
      plugins: [["@babel/plugin-syntax-decorators", { legacy: true }]],
      presets: [reactCompilerPreset()],
    }),
    tailwindcss(),
  ],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
});
