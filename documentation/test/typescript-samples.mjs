import {cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync} from 'node:fs'
import {createRequire} from 'node:module'
import {dirname, join, relative} from 'node:path'
import {fileURLToPath, pathToFileURL} from 'node:url'
import ts from 'typescript'

// The TypeScript samples of the TypeScript pages and the package READMEs,
// checked against the built packages so that a sample that drifts from the API
// fails CI instead of its reader. Build the packages first:
// `pnpm --filter documentation^... build`.
//
// Every ```ts / ```tsx block is one of two kinds:
//
// - An example: it has an import or a statement that runs. It is compiled
//   strictly as its own module, in a directory per page, exactly as a reader
//   would paste it.
// - A signature: only declarations without bodies, as the reference pages
//   print them. It is compiled as a declaration file, importing every name it
//   uses but does not declare from the packages; a name no package exports is
//   an error, and so is a declared name its package does not export.
//
// HTML comments, invisible on the site and on npm, steer the block after them:
//
//   <!-- typecheck: skip — why -->      not TypeScript on its own (a JSX fragment, a before/after)
//   <!-- typecheck: file=cart.ts -->    the module's name, so later blocks import './cart'
//   <!-- typecheck-context
//   declare const snapshots: SnapshotQueryClient<unknown>
//   -->                                 prepended to the block: what the prose around it defined
//
// A ```json block with `file=` is written too. Anywhere in a page:
//
//   <!-- typecheck-generated: typescript/integration-test/src/generated -->
//       copy committed generator output into the page's directory as ./generated
//   <!-- typecheck-generate: openapi.json -->
//       run wow-generator on the page's block named openapi.json into ./generated

const repository = fileURLToPath(new URL('../../', import.meta.url))
const documentation = join(repository, 'documentation')
// Inside documentation/node_modules, so that the samples resolve the packages
// the documentation package depends on.
const work = join(documentation, 'node_modules/.cache/typescript-samples')

const markdown = (dir) =>
    existsSync(dir)
        ? readdirSync(dir, {recursive: true})
              .map((file) => file.split('\\').join('/'))
              .filter((file) => file.endsWith('.md'))
              .map((file) => join(dir, file))
        : []

export const SOURCES = [
    ...['en', 'zh'].flatMap((locale) => [
        ...markdown(join(documentation, `docs/${locale}/guide/typescript`)),
        ...markdown(join(documentation, `docs/${locale}/reference/typescript`)),
    ]),
    ...['wow-client', 'wow-generator', 'wow-view-engine'].flatMap((pkg) =>
        ['README.md', 'README.zh-CN.md'].map((readme) => join(repository, 'typescript', pkg, readme)),
    ),
].sort()

/** Entry points a signature may take names from, by package. */
const ENTRIES = {
    'wow-client': ['@ahoo-wang/wow-client', '@ahoo-wang/wow-client/legacy', '@ahoo-wang/wow-client/dsl'],
    'wow-react': ['@ahoo-wang/wow-react'],
    'wow-generator': ['@ahoo-wang/wow-generator'],
    'wow-view-engine': ['@ahoo-wang/wow-view-engine', '@ahoo-wang/wow-view-engine/react', '@ahoo-wang/wow-view-engine/ui'],
    fetcher: [
        '@ahoo-wang/fetcher',
        '@ahoo-wang/fetcher-decorator',
        '@ahoo-wang/fetcher-eventstream',
        '@ahoo-wang/fetcher-react',
        '@ahoo-wang/fetcher-openapi',
        '@ahoo-wang/fetcher-cosec',
    ],
}

/** The package a page documents, whose entries come first. */
function packageOf(page) {
    const match = /(?:reference\/typescript\/|^typescript\/)(wow-[a-z-]+)/.exec(page)
    return match && ENTRIES[match[1]] ? match[1] : 'wow-client'
}

const FENCE = /^( *)(`{3,}|~{3,})\s*([\w-]*)(.*)$/
const TYPESCRIPT = new Set(['ts', 'tsx', 'typescript'])

/** The fenced blocks of a page, with the directives written just before each. */
export function blocks(text) {
    const lines = text.split('\n')
    const found = []
    for (let index = 0; index < lines.length; index++) {
        const open = FENCE.exec(lines[index])
        if (!open) continue
        const [, indent, fence, language] = open
        let end = index + 1
        while (end < lines.length && !lines[end].trimStart().startsWith(fence)) end++
        const code = lines
            .slice(index + 1, end)
            .map((line) => (line.startsWith(indent) ? line.slice(indent.length) : line.trimStart()))
            .join('\n')
        found.push({line: index + 1, language, code, ...directives(lines, index)})
        index = end
    }
    return found
}

/** The HTML comments written between the previous paragraph and a fence. */
function directives(lines, fence) {
    const comments = []
    let at = fence - 1
    while (at >= 0 && lines[at].trim() === '') at--
    while (at >= 0 && lines[at].trim().endsWith('-->')) {
        let start = at
        while (start >= 0 && !lines[start].includes('<!--')) start--
        if (start < 0) break
        comments.unshift(lines.slice(start, at + 1).join('\n'))
        at = start - 1
        while (at >= 0 && lines[at].trim() === '') at--
    }
    const result = {skip: undefined, file: undefined, context: ''}
    for (const comment of comments) {
        const body = comment.trim().replace(/^<!--/, '').replace(/-->$/, '')
        const head = /^\s*typecheck(-context)?\s*:?\s*/.exec(body)
        if (!head) continue
        const rest = body.slice(head[0].length)
        if (head[1]) result.context += `${rest.trim()}\n`
        else if (/^skip\b/.test(rest)) result.skip = rest.replace(/^skip[\s:—–-]*/, '').trim()
        else if (/^file=/.test(rest)) result.file = rest.replace(/^file=/, '').trim()
    }
    return result
}

/** Page-wide directives. */
function pageDirectives(text) {
    return {
        generated: [...text.matchAll(/<!--\s*typecheck-generated:\s*(\S+)\s*-->/g)].map(([, path]) => path),
        generate: [...text.matchAll(/<!--\s*typecheck-generate:\s*(\S+)\s*-->/g)].map(([, path]) => path),
    }
}

/** A block of declarations only, without an import or a body: a signature. */
export function isSignature(code, language) {
    const source = ts.createSourceFile(`block.${language === 'tsx' ? 'tsx' : 'ts'}`, code, ts.ScriptTarget.Latest, false)
    if (source.statements.length === 0) return false
    return source.statements.every((statement) => {
        switch (statement.kind) {
            case ts.SyntaxKind.InterfaceDeclaration:
            case ts.SyntaxKind.TypeAliasDeclaration:
            case ts.SyntaxKind.EnumDeclaration:
            case ts.SyntaxKind.ModuleDeclaration:
                return true
            case ts.SyntaxKind.FunctionDeclaration:
                return !statement.body
            case ts.SyntaxKind.ClassDeclaration:
                return statement.members.every((member) => !member.body)
            case ts.SyntaxKind.VariableStatement:
                // `export const X = …` is how a page prints an exported constant.
                return (
                    statement.modifiers?.some((modifier) => modifier.kind === ts.SyntaxKind.ExportKeyword) ||
                    statement.declarationList.declarations.every((declaration) => !declaration.initializer)
                )
            default:
                return false
        }
    })
}

/** The top-level names a signature declares. */
function declaredNames(code) {
    const source = ts.createSourceFile('block.ts', code, ts.ScriptTarget.Latest, false)
    return source.statements.flatMap((statement) =>
        ts.isVariableStatement(statement)
            ? statement.declarationList.declarations.map((declaration) => declaration.name.getText(source))
            : statement.name
              ? [statement.name.getText(source)]
              : [],
    )
}

const pageDir = (source) => join(work, relative(repository, source).replace(/\.md$/, '').replace(/[^\w/-]/g, '_'))

const COMPILER_OPTIONS = {
    target: 'ES2022',
    module: 'ESNext',
    moduleResolution: 'Bundler',
    lib: ['ES2023', 'DOM', 'DOM.Iterable'],
    jsx: 'react-jsx',
    strict: true,
    experimentalDecorators: true,
    skipLibCheck: true,
    noEmit: true,
    resolveJsonModule: true,
    types: ['node'],
    typeRoots: [join(documentation, 'node_modules/@types')],
}

function program(files) {
    const config = ts.parseJsonConfigFileContent(
        {compilerOptions: COMPILER_OPTIONS, files: [join(work, 'assets.d.ts'), ...files]},
        ts.sys,
        work,
    )
    return ts.createProgram({rootNames: config.fileNames, options: config.options})
}

/** Which entries export each name, in the order ENTRIES lists them. */
function exportedNames() {
    const specifiers = Object.values(ENTRIES).flat()
    const file = join(work, 'entries.ts')
    writeFileSync(file, specifiers.map((specifier, index) => `import * as m${index} from '${specifier}'`).join('\n'))
    const entries = program([file])
    const checker = entries.getTypeChecker()
    const imports = entries.getSourceFile(file).statements
    return specifiers.map((specifier, index) => {
        const symbol = checker.getSymbolAtLocation(imports[index].moduleSpecifier)
        if (!symbol) throw new Error(`${specifier} does not resolve; build the packages first`)
        return {specifier, names: new Set(checker.getExportsOfModule(symbol).map((exported) => exported.name))}
    })
}

async function prepare() {
    rmSync(work, {recursive: true, force: true})
    mkdirSync(work, {recursive: true})
    // What a Vite project's `vite/client` types declare for stylesheet imports.
    writeFileSync(join(work, 'assets.d.ts'), "declare module '*.css'\n")
    const samples = []
    for (const source of SOURCES) {
        if (!existsSync(source)) continue
        const text = readFileSync(source, 'utf8')
        const dir = pageDir(source)
        const page = relative(repository, source)
        let count = 0
        for (const block of blocks(text)) {
            if (block.file && !TYPESCRIPT.has(block.language)) {
                mkdirSync(dir, {recursive: true})
                writeFileSync(join(dir, block.file), block.code)
                continue
            }
            if (!TYPESCRIPT.has(block.language) || block.skip !== undefined) continue
            count++
            const signature = !block.context && !block.file && isSignature(block.code, block.language)
            const extension = signature ? 'd.ts' : block.language === 'tsx' ? 'tsx' : 'ts'
            const file = join(dir, block.file ?? `sample-${String(count).padStart(3, '0')}-L${block.line}.${extension}`)
            mkdirSync(dirname(file), {recursive: true})
            writeFileSync(file, `${block.context}${block.code}\nexport {}\n`)
            samples.push({page, line: block.line, file, signature, code: block.code, prefix: block.context.split('\n').length - 1})
        }
        const {generated, generate} = pageDirectives(text)
        for (const path of generated) {
            mkdirSync(dir, {recursive: true})
            cpSync(join(repository, path), join(dir, 'generated'), {recursive: true})
        }
        for (const spec of generate) await runGenerator(join(dir, spec), join(dir, 'generated'))
    }
    return samples
}

async function runGenerator(inputPath, outputDir) {
    const require = createRequire(join(documentation, 'package.json'))
    const {CodeGenerator, SilentLogger} = await import(pathToFileURL(require.resolve('@ahoo-wang/wow-generator')).href)
    await new CodeGenerator({inputPath, outputDir, logger: new SilentLogger()}).generate()
}

// A signature compiles as far as its names go: a name it uses must exist.
const UNKNOWN_NAME = new Set([2304, 2503, 2552, 2694, 2724, 2305])

/** Compiles every sample; answers the problems, located in the Markdown source. */
export async function check() {
    const samples = await prepare()
    const exported = exportedNames()
    const order = (page) => {
        const first = packageOf(page)
        return [...ENTRIES[first], ...Object.entries(ENTRIES).flatMap(([pkg, entries]) => (pkg === first ? [] : entries))]
    }
    // Signatures import what they use from the first entry that exports it.
    for (const sample of samples.filter(({signature}) => signature)) {
        const declared = new Set(declaredNames(sample.code))
        const used = new Set([...sample.code.matchAll(/\b[A-Za-z_$][\w$]*\b/g)].map(([name]) => name))
        const imports = new Map()
        for (const name of used) {
            if (declared.has(name)) continue
            const entry = order(sample.page)
                .map((specifier) => exported.find((candidate) => candidate.specifier === specifier))
                .find((candidate) => candidate.names.has(name))
            if (!entry) continue
            if (!imports.has(entry.specifier)) imports.set(entry.specifier, [])
            imports.get(entry.specifier).push(name)
        }
        const header = [...imports].map(([specifier, names]) => `import type {${names.join(', ')}} from '${specifier}'`)
        sample.prefix = header.length
        writeFileSync(sample.file, `${header.map((line) => `${line}\n`).join('')}${sample.code}\nexport {}\n`)
        const own = ENTRIES[packageOf(sample.page)].map((specifier) => exported.find((candidate) => candidate.specifier === specifier))
        sample.missing = [...declared].filter((name) => !own.some((entry) => entry.names.has(name)))
    }
    const byFile = new Map(samples.map((sample) => [sample.file, sample]))
    const failures = []
    for (const sample of samples)
        for (const name of sample.missing ?? [])
            failures.push(`${sample.page}:${sample.line}: ${name} is documented but ${packageOf(sample.page)} does not export it`)
    for (const diagnostic of ts.getPreEmitDiagnostics(program(samples.map(({file}) => file)))) {
        const message = ts.flattenDiagnosticMessageText(diagnostic.messageText, '\n')
        const sample = diagnostic.file && byFile.get(diagnostic.file.fileName)
        if (!sample) {
            failures.push(diagnostic.file ? `${relative(work, diagnostic.file.fileName)}: TS${diagnostic.code} ${message}` : message)
            continue
        }
        if (sample.signature && !UNKNOWN_NAME.has(diagnostic.code)) continue
        const {line} = diagnostic.file.getLineAndCharacterOfPosition(diagnostic.start ?? 0)
        const at = line - sample.prefix
        failures.push(`${sample.page}:${at < 0 ? `${sample.line} (context)` : sample.line + 1 + at}: TS${diagnostic.code} ${message}`)
    }
    return {samples, failures}
}

/** The skipped samples, with the reason each gives. */
export function skipped() {
    return SOURCES.filter(existsSync).flatMap((source) =>
        blocks(readFileSync(source, 'utf8'))
            .filter((block) => TYPESCRIPT.has(block.language) && block.skip !== undefined)
            .map((block) => ({page: relative(repository, source), line: block.line, reason: block.skip})),
    )
}

export const built = (pkg) => existsSync(join(repository, 'typescript', pkg, 'dist'))

// `node documentation/test/typescript-samples.mjs` prints every problem.
if (process.argv[1] === fileURLToPath(import.meta.url)) {
    const {samples, failures} = await check()
    for (const failure of failures) console.log(failure)
    const signatures = samples.filter(({signature}) => signature).length
    console.log(
        `${samples.length - signatures} examples, ${signatures} signatures, ${skipped().length} skipped, ${failures.length} problems`,
    )
    process.exitCode = failures.length ? 1 : 0
}
