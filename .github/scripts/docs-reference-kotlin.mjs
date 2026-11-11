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

/** The annotation a Kotlin source declares, or undefined: `{ name, kdoc, targets, parameters }`. */
export function annotationOf(source) {
  const declaration =
    /\n((?:@[\w.]+(?:\([^)]*\))?\s*\n)*)annotation class (\w+)(?:\s*\(([\s\S]*?)\n\))?/.exec(
      source,
    );
  if (!declaration) return undefined;
  const before = source.slice(0, declaration.index);
  const kdoc = /\/\*\*([\s\S]*?)\*\/\s*$/.exec(before)?.[1] ?? '';
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
  const parameters = topLevel(declaration[3] ?? '')
    .map(part =>
      part
        .replace(/\/\/.*$/gm, '')
        .replace(/\s+/g, ' ')
        .trim(),
    )
    .map(part => /^val (\w+):\s*(.+?)(?:\s*=\s*(.+))?$/.exec(part))
    .filter(Boolean)
    .map(match => ({
      name: match[1],
      type: match[2].trim(),
      default: match[3]
        ?.trim()
        .replace(/\[\s+/g, '[')
        .replace(/,\s*]$/, ']'),
    }));
  return { name: declaration[2], summary, targets, parameters };
}

const DECLARATION =
  /^(\s*)((?:(?:public|internal|private|protected|override|open|abstract|sealed|data|value|enum|annotation|inline|suspend|operator|infix|const|lateinit|expect|actual)\s+)*)(fun\s+interface|class|interface|object|fun|val|var|typealias|constructor)\b\s*(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)?/;

/** The public declarations of a Kotlin source that carry `@marker`: `[{ name, kind }]`, members by their type. */
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
      types.push({ indent: indent.length, name, hidden });
    marked = false;
  }
  return found;
}
