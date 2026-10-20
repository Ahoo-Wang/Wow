// Enables full profiling on every user database, with a large capped profile collection.
const dbs = db.adminCommand({listDatabases: 1}).databases.map(d => d.name).filter(n => !['admin', 'local', 'config'].includes(n));
for (const name of dbs) {
  const d = db.getSiblingDB(name);
  d.setProfilingLevel(0);
  d.system.profile.drop();
  d.createCollection('system.profile', {capped: true, size: 512 * 1024 * 1024});
  d.setProfilingLevel(2);
  print('profiling ' + name);
}
