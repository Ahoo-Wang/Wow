import { readFileSync, writeFileSync } from 'node:fs';
const S = process.argv[2];
const list = readFileSync(S + '/prlist.txt', 'utf8').trim().split('\n').map(l => +l.split(' ')[0]);
const skip = new Set([7181, 7198]);
const out = {};
const cjk = s => (s.match(/[一-鿿]/g) || []).length;
for (const n of list) {
  if (n <= 7015 || skip.has(n)) continue;
  const body = JSON.parse(readFileSync(`${S}/prs/${n}.json`, 'utf8')).body.replace(/\r\n?/g, '\n');
  const lines = body.split('\n');
  const i = lines.findIndex(l => /^#{2,3}\s+Upgrades? paragraph/i.test(l));
  if (i < 0) continue;
  const lvl = lines[i].match(/^#+/)[0].length;
  let j = lines.findIndex((l, k) => k > i && /^#{1,6}\s/.test(l) && l.match(/^#+/)[0].length <= lvl);
  const text = lines.slice(i + 1, j < 0 ? undefined : j).join('\n').trim();
  if (/^None[:.]/.test(text) || /^Nothing changes for an application/.test(text)) { out[n] = { none: text }; continue; }
  const blocks = text.split(/\n\s*\n/).map(b => b.trim()).filter(b => b && !/^Refs #/.test(b));
  const en = [], zh = [];
  for (const b of blocks) {
    const c = cjk(b);
    let t = b.replace(/^\*\*English[.:]\*\*\s*/, '').replace(/^\*\*中文[。：]\*\*\s*/, '');
    (c > 8 ? zh : en).push(t);
  }
  out[n] = { en, zh, title: JSON.parse(readFileSync(`${S}/prs/${n}.json`, 'utf8')).title };
}
writeFileSync(S + '/paragraphs.json', JSON.stringify(out, null, 1));
let enAll = '';
for (const [n, p] of Object.entries(out).sort((a, b) => a[0] - b[0])) {
  if (p.none) { enAll += `\n## #${n} NONE: ${p.none}\n`; continue; }
  enAll += `\n## #${n} (en ${p.en.length} / zh ${p.zh.length})\n${p.en.join('\n\n')}\n`;
}
writeFileSync(S + '/paragraphs-en.md', enAll);
