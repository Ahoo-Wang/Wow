import { readFileSync } from 'node:fs';
const [S, name, ...ns] = process.argv.slice(2);
for (const n of ns) {
  const body = JSON.parse(readFileSync(`${S}/prs/${n}.json`, 'utf8')).body.replace(/\r\n?/g, '\n');
  const lines = body.split('\n'); const re = new RegExp('^#{2,3}\\s+' + name, 'i');
  const i = lines.findIndex(l => re.test(l));
  if (i < 0) { console.log(`#${n} [no ${name}]`); continue; }
  const lvl = lines[i].match(/^#+/)[0].length;
  let j = lines.findIndex((l, k) => k > i && /^#{1,6}\s/.test(l) && l.match(/^#+/)[0].length <= lvl);
  console.log(`#${n} ${lines[i]}\n${lines.slice(i + 1, j < 0 ? undefined : j).join('\n').trim()}\n`);
}
