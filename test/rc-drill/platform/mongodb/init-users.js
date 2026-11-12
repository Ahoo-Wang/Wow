// The services' users, one per service, each with readWrite on its own database (the `database` of its mongo profile),
// and drill-reader, which reads every database for the drill's verdict (shell.yaml). The passwords are in the
// mongodb-users secret, one key per user (README.md, "Secrets"). Run as root on the primary; a user that exists is left
// as it is, so the job can run again.
const users = {
  "eshop-catalog": ["eshop_catalog"],
  "eshop-basket": ["eshop_basket"],
  "eshop-ordering": ["eshop_ordering"],
  "eshop-inventory": ["eshop_inventory"],
  "eshop-payment": ["eshop_payment"],
  "ops": ["wow_ops"],
  "view-store": ["view_store"],
  "analytics-catalog": ["wow_analytics_catalog"],
};
const admin = db.getSiblingDB("admin");
const passwordOf = (user) => {
  const key = user.toUpperCase().replace(/-/g, "_");
  const password = process.env[key];
  if (!password) throw new Error(`mongodb-users has no ${key}`);
  return password;
};
const ensure = (user, roles) => {
  if (admin.getUser(user)) {
    print(`${user}: exists`);
    return;
  }
  admin.createUser({ user, pwd: passwordOf(user), roles });
  print(`${user}: created`);
};
for (const [user, databases] of Object.entries(users)) {
  ensure(user, databases.map((name) => ({ role: "readWrite", db: name })));
}
ensure("drill-reader", [{ role: "readAnyDatabase", db: "admin" }]);
