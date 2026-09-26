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

/*
 * What a docs page's 「Show code」 shows: the real source of the host's side
 * of a scene, read from the file itself (`?raw`), not the one-line
 * `<OrderScene />` Storybook would print for a story whose render lives in
 * its meta. A code sample kept beside the story would drift from it; the
 * file cannot.
 */

/** A source file without the licence header it opens with. */
export function readable(source: string): string {
  return source.replace(/^\/\*[\s\S]*?\*\/\s*/, '').trimEnd();
}

/**
 * Where a story file's catalogue entry starts: its docs description, this
 * page's own `HOST_CODE`, or the `meta` — Storybook's, not a host's. Every
 * scene file declares its components above them.
 */
const CATALOGUE =
  /^(?:const description\b|const meta\b|\/\*\* What 「Show code」)/m;

/** The imports only the catalogue entry needs: Storybook's, and this. */
const CATALOGUE_IMPORT =
  /^import [^;]*?(?:\?raw'|\/hostSource\.js'|'@storybook\/[^']+'|'storybook\/[^']+');\n/gm;

/**
 * The host's part of a story file: its imports and the components that wire
 * the engine, without the catalogue entry below them. Several files are
 * shown one after another, each under its own name.
 */
export function hostSource(
  ...files: readonly (readonly [name: string, source: string])[]
): string {
  return files
    .map(([name, source]) => {
      const body = readable(source).replace(CATALOGUE_IMPORT, '');
      const cut = body.search(CATALOGUE);
      return `// ${name}\n\n${(cut < 0 ? body : body.slice(0, cut)).trimEnd()}`;
    })
    .join('\n\n');
}
