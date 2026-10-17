const s = db.adminCommand({serverStatus: 1});
const cmds = {};
for (const k in s.metrics.commands) { const v = s.metrics.commands[k]; if (v && v.total !== undefined) cmds[k] = Number(v.total); }
print(JSON.stringify({
  opcounters: Object.fromEntries(Object.entries(s.opcounters).map(([k, v]) => [k, Number(v)])),
  commands: cmds,
  txn: {committed: Number(s.transactions.totalCommitted), started: Number(s.transactions.totalStarted), aborted: Number(s.transactions.totalAborted)},
}));
