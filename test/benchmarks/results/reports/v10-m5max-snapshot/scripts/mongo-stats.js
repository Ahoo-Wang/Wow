// Extracts WiredTiger cache and transaction checkpoint stats from MongoDB
const s = db.serverStatus();
const toNum = (v) => {
  if (v === undefined || v === null) return 0;
  if (typeof v === 'number') return v;
  if (typeof v === 'object' && v.toNumber) return v.toNumber();
  return Number(v);
};
const out = {
  "bytes currently in the cache": toNum(s.wiredTiger?.cache?.["bytes currently in the cache"]),
  "maximum bytes configured": toNum(s.wiredTiger?.cache?.["maximum bytes configured"]),
  "unmodified pages evicted": toNum(s.wiredTiger?.cache?.["unmodified pages evicted"]),
  "modified pages evicted": toNum(s.wiredTiger?.cache?.["modified pages evicted"]),
  "transaction checkpoint max time (msecs)": toNum(s.wiredTiger?.transaction?.["transaction checkpoint max time (msecs)"]),
  "transaction checkpoint most recent time (msecs)": toNum(s.wiredTiger?.transaction?.["transaction checkpoint most recent time (msecs)"])
};
print(JSON.stringify(out, null, 2));
