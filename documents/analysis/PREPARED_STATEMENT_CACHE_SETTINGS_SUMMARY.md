# OJP Prepared Statement Cache Settings - Summary

**Full Analysis:** [PREPARED_STATEMENT_CACHE_SETTINGS_ANALYSIS.md](PREPARED_STATEMENT_CACHE_SETTINGS_ANALYSIS.md)

---

## Quick Answer

Yes, OJP should expose prepared statement cache settings in **OJP standard properties notation**, keep them **enabled by default**, and translate them to driver-specific datasource properties at runtime on the server side.

---

## Proposed Standard Properties (Server-Side Canonical)

### Non-XA

```properties
ojp.connection.pool.statementCache.enabled=true
ojp.connection.pool.statementCache.maxSize=250
ojp.connection.pool.statementCache.sqlLimit=2048
ojp.connection.pool.statementCache.serverPrepare=true
ojp.connection.pool.statementCache.prepareThreshold=5
```

### XA

```properties
ojp.xa.connection.pool.statementCache.enabled=true
ojp.xa.connection.pool.statementCache.maxSize=250
ojp.xa.connection.pool.statementCache.sqlLimit=2048
ojp.xa.connection.pool.statementCache.serverPrepare=true
ojp.xa.connection.pool.statementCache.prepareThreshold=5
```

### Optional Per-Database Overrides

```properties
ojp.connection.pool.statementCache.postgres.prepareThreshold=3
ojp.connection.pool.statementCache.mysql.maxSize=300
```

Also supported with datasource prefix:

```properties
orders.ojp.connection.pool.statementCache.maxSize=500
orders.ojp.connection.pool.statementCache.postgres.prepareThreshold=3
```

---

## Runtime Translation Model

At datasource creation time, OJP resolves canonical statement cache settings and translates them to database-driver properties:

- **MySQL**: `cachePrepStmts`, `prepStmtCacheSize`, `prepStmtCacheSqlLimit`, `useServerPrepStmts`
- **PostgreSQL/Cockroach**: `prepareThreshold` (and optional driver cache keys when explicitly enabled)
- **SQL Server**: `disableStatementPooling=false`, `statementPoolingCacheSize`
- **Oracle**: `oracle.jdbc.implicitStatementCacheSize`
- **DB2**: `maxStatements`
- **MariaDB/H2**: map only safe/supported keys; otherwise no-op

If unsupported for a given driver/version, OJP should skip the mapping and log a clear message (no bootstrap failure).

---

## Precedence and Safety

Property precedence (highest to lowest):
1. datasource-scoped key (`<ds>.ojp...`)
2. global key (`ojp...`)
3. OJP default

Safety rules:
- `enabled=false` means no statement-cache driver properties are emitted.
- Invalid numeric values fall back to defaults with warning.
- Effective resolved values are logged once per datasource/pool creation.

---

## Why This Fits OJP

- It keeps configuration database-agnostic for users.
- It centralizes portability logic in OJP server.
- It aligns with existing OJP property-loading patterns.
- It is compatible with existing pool provider pass-through of datasource properties.

---

## Status

- **Design only** (this document).
- **No code changes proposed in this report.**

