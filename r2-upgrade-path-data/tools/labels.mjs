import { readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
const repo = process.argv[2], S = process.argv[3];
const { isBreakingTitle, isBreakingBody, removedSurfaceLines, isSurfaceFile } = await import(repo + '/.github/scripts/breaking-label.mjs');
const list = readFileSync(S + '/prlist.txt', 'utf8').trim().split('\n').map(l => l.split(' '));
const out = [];
for (const [n, h] of list) {
  const pr = JSON.parse(readFileSync(`${S}/prs/${n}.json`, 'utf8'));
  const files = execFileSync('git', ['-C', repo, 'diff', '--name-status', '-M', `${h}^`, h], { encoding: 'utf8' }).trim().split('\n').filter(Boolean);
  const removals = [];
  for (const f of files) {
    const parts = f.split('\t'); const path = parts[parts.length - 1]; const prev = parts.length > 2 ? parts[1] : undefined;
    if (!isSurfaceFile(path) && !isSurfaceFile(prev)) continue;
    const args = ['-C', repo, 'diff', '-M', `${h}^`, h, '--', ...(prev ? [prev, path] : [path])];
    const patch = execFileSync('git', args, { encoding: 'utf8', maxBuffer: 1 << 28 }).split('\n').filter(l => !l.startsWith('---') && !l.startsWith('+++')).join('\n');
    const lines = removedSurfaceLines({ filename: path, previous_filename: prev, patch });
    if (lines.length) removals.push({ file: path, n: lines.length, first: lines[0].trim() });
  }
  const labelled = pr.labels.some(l => l.name === 'breaking-change');
  const t = isBreakingTitle(pr.title), b = isBreakingBody(pr.body);
  out.push({ n: +n, labelled, title: t, body: b, removals, t: pr.title, author: pr.author.login });
}
writeFileSync(S + '/labels.json', JSON.stringify(out, null, 1));
for (const o of out) {
  const rule = o.title || o.body || o.removals.length > 0;
  if (rule !== o.labelled) console.log(`MISMATCH #${o.n} labelled=${o.labelled} title=${o.title} body=${o.body} surf=${o.removals.map(r => r.file + ':' + r.n + ' ' + r.first).join(' | ')} :: ${o.t}`);
}
console.log('labelled', out.filter(o => o.labelled).length, 'of', out.length);
