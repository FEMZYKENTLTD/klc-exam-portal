#!/usr/bin/env python3
"""KLC v1.0 SQL <-> schema cross-check.

Builds the schema from DatabaseInitializer's H2 DDL (the Java-visible
baseline; cloud mirrors it via migrations), then verifies every SQL
statement in the Java code:
  - referenced tables exist
  - INSERT INTO t(col,...) columns exist in t
  - UPDATE t SET col=? columns exist in t
"""
import re, os, glob, sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
JAVA = os.path.join(ROOT, "src", "main", "java")
INIT = os.path.join(JAVA, "com", "femzyk", "klc", "db", "DatabaseInitializer.java")

def read(p):
    with open(p, encoding="utf-8", errors="replace") as f:
        return f.read()

# ---------------------------------------------------------------- schema
init_src = read(INIT)

# merge Java-concatenated string literals: "a" +\n "b"  ->  "a\nb"
merged = re.sub(r'"\s*\+\s*\n\s*"', '', init_src)

schema = {}   # table -> set(columns)
for m in re.finditer(
        r'CREATE TABLE IF NOT EXISTS (\w+)\s*\((.*?)\)"',
        merged, re.S):
    t, body = m.group(1).lower(), m.group(2)
    cols = set()
    for line in body.split(','):
        line = line.strip()
        cm = re.match(r'(\w+)\s+(VARCHAR|TEXT|INT|INTEGER|BOOLEAN|TIMESTAMP|'
                      r'DATE|NUMERIC|CLOB|DOUBLE|BIGINT|CHAR|UUID)', line, re.I)
        if cm:
            cols.add(cm.group(1).lower())
    # The same table can be defined twice (H2 DDL + Postgres startup DDL in
    # ensurePostgresColumns) - union the column sets instead of overwriting.
    schema.setdefault(t, set()).update(cols)

# ALTER TABLE x ADD COLUMN IF NOT EXISTS col
for m in re.finditer(
        r'ALTER TABLE (\w+)\s+ADD COLUMN IF NOT EXISTS\s+(\w+)', merged):
    schema.setdefault(m.group(1).lower(), set()).add(m.group(2).lower())

# manual additions that live in migration SQL but are used by Java
for t, cols in {
    "user_profiles":  {"id","user_id","photo_url","bio","date_of_birth","address","updated_at"},
    "friendships":    {"id","requester_id","receiver_id","status","created_at"},
    "messages":       {"id","sender_id","receiver_id","content","is_read","created_at"},
    "parent_profiles":{"id","user_id","ward_admission_no","relationship"},
}.items():
    schema.setdefault(t, set()).update(cols)

print("=" * 72)
print(f"Schema built: {len(schema)} tables")
for t in sorted(schema): print(f"  {t:24s} {len(schema[t])} cols")
print("=" * 72)

# ---------------------------------------------------------------- Java SQL
problems = []
table_refs = {}
for jf in glob.glob(os.path.join(JAVA, "**", "*.java"), recursive=True):
    src = read(jf)
    merged_java = re.sub(r'"\s*\+\s*\n?\s*"', '', src)
    rel = os.path.relpath(jf, JAVA)
    # SQL-looking literals only (prepares/executes)
    for sqlm in re.finditer(r'"((?:SELECT|INSERT|UPDATE|DELETE|CREATE TABLE)[^"]*)"', merged_java):
        sql = sqlm.group(1)
        for m in re.finditer(
                r'\b(?:FROM|JOIN|UPDATE|INTO)\s+(\w+)', sql, re.I):
            t = m.group(1).lower()
            if t in ("a", "b", "the"): continue
            table_refs.setdefault(t, set()).add(rel)

    # INSERT INTO t(col,...)
    for m in re.finditer(
            r'INSERT INTO (\w+)\s*\(([^)]*)\)', merged_java, re.I):
        t, cols = m.group(1).lower(), m.group(2)
        if t not in schema:
            problems.append(f"[TABLE-MISSING] {rel}: INSERT INTO {t} "
                            f"- no such table in schema")
            continue
        for c in cols.split(','):
            c = c.strip().lower()
            if c and c not in schema[t]:
                problems.append(f"[COL-MISSING] {rel}: INSERT INTO {t}({c}) "
                                f"- column not in DDL")
    # UPDATE t SET col=?, col2=?
    # Stop at the first WHERE or at the closing quote of the Java string
    # literal - without the quote boundary, statements without WHERE run
    # on to EOF and swallow trailing Java code (e.g. "campusField = ..."
    # looked like a SET column).
    for m in re.finditer(
            r'UPDATE (\w+)\s+SET\s+(.*?)(?:\bWHERE\b|")', merged_java, re.I | re.S):
        t, sets = m.group(1).lower(), m.group(2)
        if t not in schema:
            problems.append(f"[TABLE-MISSING] {rel}: UPDATE {t}")
            continue
        for pair in sets.split(','):
            cm = re.match(r'\s*(\w+)\s*=', pair)
            if cm:
                c = cm.group(1).lower()
                if c not in schema[t]:
                    problems.append(f"[COL-MISSING] {rel}: UPDATE {t} SET {c}"
                                    f" - column not in DDL")

# tables referenced but never created
for t, files in sorted(table_refs.items()):
    if t not in schema:
        problems.append(f"[TABLE-MISSING] referenced {t} in: "
                        f"{', '.join(sorted(files))}")

print(f"\nPROBLEMS ({len(problems)}):")
for p in sorted(set(problems)): print("  x " + p)
sys.exit(1 if problems else 0)
