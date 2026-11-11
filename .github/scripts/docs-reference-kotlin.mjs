// The readers of Kotlin sources that the reference generator (docs-reference.mjs) builds its pages from: the
// annotations of a file, and the declarations a stability marker marks.

/** The parts of a parameter list, split at its top-level commas. */
const topLevel = text => {
  const parts = [''];
  let depth = 0;
  for (const char of text) {
    if ('([{<'.includes(char)) depth++;
    if (')]}>'.includes(char)) depth--;
    if (char === ',' && depth === 0) parts.push('');
    else parts[parts.length - 1] += char;
  }
  return parts.filter(part => part.trim());
};

/**
 * The annotations a Kotlin source declares, in their order: `[{ name, summary, targets, parameters }]`. A parameter
 * list is on the declaration's line or on lines of its own; a `vararg` parameter says so.
 */
export function annotationsOf(source) {
  const declarations = source.matchAll(
    /\n((?:@[\w.]+(?:\([^)]*\))?\s*\n)*)annotation class (\w+)(?:\(\s*\n([\s\S]*?)\n\)|\(([^\n]*)\))?/g,
  );
  return [...declarations].map(declaration => {
    const before = source.slice(0, declaration.index);
    // The KDoc that ends right before this declaration, not one of an earlier declaration.
    const kdoc =
      /\/\*\*((?:(?!\/\*\*)[\s\S])*?)\*\/\s*$/.exec(before)?.[1] ?? '';
    const text = kdoc
      .split('\n')
      .map(line => line.replace(/^\s*\* ?/, ''))
      .join('\n');
    // The prose before the first code block, tag or list.
    const summary = text
      .split(/\n\s*\n|\n```|\n@\w|\n- /)[0]
      .replace(/\s+/g, ' ')
      .trim();
    const targets = [
      ...(/@Target\(([^)]*)\)/.exec(declaration[1])?.[1] ?? '').matchAll(
        /AnnotationTarget\.(\w+)/g,
      ),
    ].map(match => match[1]);
    const parameters = topLevel(declaration[3] ?? declaration[4] ?? '')
      .map(part =>
        part
          .replace(/\/\/.*$/gm, '')
          .replace(/\s+/g, ' ')
          .trim(),
      )
      .map(part => /^(vararg )?val (\w+):\s*(.+?)(?:\s*=\s*(.+))?$/.exec(part))
      .filter(Boolean)
      .map(match => ({
        name: match[2],
        type: match[3].trim(),
        ...(match[1] ? { vararg: true } : {}),
        default: match[4]
          ?.trim()
          .replace(/\[\s+/g, '[')
          .replace(/,\s*]$/, ']'),
      }));
    return { name: declaration[2], summary, targets, parameters };
  });
}

/** The first annotation a Kotlin source declares, or undefined. */
export const annotationOf = source => annotationsOf(source)[0];

const DECLARATION =
  /^(\s*)((?:(?:public|internal|private|protected|override|open|abstract|sealed|data|value|enum|annotation|inline|suspend|operator|infix|const|lateinit|expect|actual)\s+)*)(fun\s+interface|class|interface|object|fun|val|var|typealias|constructor)\b\s*(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)?/;

/**
 * The public declarations of a Kotlin source that carry `@marker`: `[{ name, kind }]`, members by their type. An
 * entry of an enum class is one too, of kind `enum entry`.
 */
export function markedDeclarations(source, marker) {
  const pkg = /^package ([\w.]+)/m.exec(source)?.[1] ?? '';
  const lines = source.split('\n');
  const types = [];
  const found = [];
  let marked = false;
  for (const line of lines) {
    const declaration = DECLARATION.exec(line);
    if (new RegExp(`^\\s*@${marker}\\b`).test(line) && !/@OptIn/.test(line)) {
      marked = true;
      continue;
    }
    if (!declaration) {
      const entry = /^(\s*)([A-Z][A-Z0-9_]*)\s*(?:[(,;{]|$)/.exec(line);
      const enumType = types.at(-1);
      if (marked && entry && enumType?.enum && !enumType.hidden) {
        while (types.length && types.at(-1).indent >= entry[1].length)
          types.pop();
        found.push({
          name: [pkg, ...types.map(type => type.name), entry[2]].join('.'),
          kind: 'enum entry',
        });
      }
      if (marked && !/^\s*(?:@|\/\/|\*|\/\*)/.test(line) && line.trim())
        marked = false;
      continue;
    }
    const [, indent, modifiers, written, name] = declaration;
    const kind = written.startsWith('fun ') ? 'interface' : written;
    while (types.length && types.at(-1).indent >= indent.length) types.pop();
    const owner = types.at(-1);
    const hidden =
      /\b(?:internal|private|protected|override)\b/.test(modifiers) ||
      owner?.hidden;
    if (marked && !hidden && kind !== 'constructor' && name) {
      found.push({
        name: [pkg, ...types.map(type => type.name), name]
          .filter(Boolean)
          .join('.'),
        kind,
      });
    }
    if (/^(?:class|interface|object)$/.test(kind) && name)
      types.push({
        indent: indent.length,
        name,
        hidden,
        enum: /\benum\b/.test(modifiers),
      });
    marked = false;
  }
  return found;
}
