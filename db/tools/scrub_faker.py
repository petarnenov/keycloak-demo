#!/usr/bin/env python3
"""Replace PII column values in sqlformat-insert files with deterministic Faker data.

Usage:
    python3 scrub_faker.py <seed_dir> [meta_file]

    <seed_dir>  directory of *.sql files produced by SQLcl `set sqlformat insert`
                (one or more `Insert into T (cols) values (...);` lines per file).
    [meta_file] optional pipe-separated column metadata
                "TABLE|COLUMN|DATATYPE|CHARLEN|NULLABLE" (one per line) used to cap
                fake values to the real column length. Defaults to <seed_dir>/meta.out
                if present.

Output: scrubbed copies under <seed_dir>/scrubbed/ .

Why Faker (not NULL/REDACTED): blanket NULL/REDACTED breaks NOT NULL, type, length,
unique and check constraints. Faker keeps values realistic, type/length-correct and
DETERMINISTIC per original value (the same entity gets the same fake name/email/...
across every table), preserving referential coherence. Only PII *string* columns
(matched by name) are faked; IDs, codes, dates, flags and keys stay real.
"""
import re, sys, glob, os, hashlib
from faker import Faker

if len(sys.argv) < 2:
    sys.exit("usage: scrub_faker.py <seed_dir> [meta_file]")
SEEDDIR = sys.argv[1]
META = sys.argv[2] if len(sys.argv) > 2 else os.path.join(SEEDDIR, "meta.out")
OUTDIR = os.path.join(SEEDDIR, "scrubbed")
os.makedirs(OUTDIR, exist_ok=True)

# (TABLE,COL) -> (datatype, charlen, nullable)
meta = {}
if os.path.exists(META):
    for ln in open(META):
        p = ln.strip().split("|")
        if len(p) == 5:
            try: cl = int(p[3])
            except: cl = 4000
            meta[(p[0].upper(), p[1].upper())] = (p[2], cl, p[4])

PII = re.compile(r"(EMAIL|NAME|PHONE|FAX|SSN|TAX_ID|TAXID|ADDR|STREET|CITY|ZIP|POSTAL|BIRTH|DOB|PSWD|PASSWORD|PASSWD|TOKEN|SECRET|ACCOUNT_NUM|LDAP_UID|NICKNAME|NOTES|CONTACT)")
# never fake these even if the name overlaps a PII token (they are keys/codes)
SKIP = re.compile(r"(FLAG|_CD$|_ID$|TYPE|DATE|_KEY$|UUID|COUNT|NUM_|_NUM$)")

def fake_for(col, fk, maxlen):
    c = col.upper()
    if "EMAIL" in c: v = fk.email()
    elif "COMPANY" in c or "FIRM" in c: v = fk.company()
    elif "GIVEN" in c or "FIRST" in c: v = fk.first_name()
    elif "SUR" in c or "LAST" in c: v = fk.last_name()
    elif "CONTACT" in c or "NICKNAME" in c: v = fk.name()
    elif "NAME" in c: v = fk.name()
    elif "PHONE" in c or "FAX" in c: v = fk.numerify("###-###-####")
    elif "STREET" in c or "ADDR" in c: v = fk.street_address().replace("\n", " ")
    elif "CITY" in c: v = fk.city()
    elif "ZIP" in c or "POSTAL" in c: v = fk.postcode()
    elif "SSN" in c or "TAX" in c: v = fk.numerify("#########")
    elif "LDAP_UID" in c: v = fk.user_name()
    elif "NOTES" in c: v = fk.sentence()
    elif any(k in c for k in ("PSWD", "PASSWORD", "PASSWD", "TOKEN", "SECRET")): v = fk.sha1()[:16]
    elif "ACCOUNT_NUM" in c: v = fk.numerify("########")
    else: v = fk.word()
    v = str(v).replace("\n", " ")[:maxlen]
    return "'" + v.replace("'", "''") + "'"

def split_top(vals):
    """Split a VALUES body on top-level commas, respecting quotes and parens."""
    out, buf, depth, i, n, inq = [], [], 0, 0, len(vals), False
    while i < n:
        ch = vals[i]
        if inq:
            buf.append(ch)
            if ch == "'":
                if i + 1 < n and vals[i+1] == "'":
                    buf.append("'"); i += 2; continue
                inq = False
            i += 1; continue
        if ch == "'":
            inq = True; buf.append(ch); i += 1; continue
        if ch == "(": depth += 1
        elif ch == ")": depth -= 1
        if ch == "," and depth == 0:
            out.append("".join(buf)); buf = []; i += 1; continue
        buf.append(ch); i += 1
    out.append("".join(buf))
    return out

INS = re.compile(r"^Insert into (\S+) \((.*?)\) values \((.*)\);\s*$", re.I)

def process(line):
    m = INS.match(line)
    if not m: return line
    tbl = m.group(1).strip('"').upper()
    cols = [c.strip().strip('"').upper() for c in m.group(2).split(",")]
    vals = split_top(m.group(3))
    if len(cols) != len(vals): return line  # don't risk mangling
    for idx, col in enumerate(cols):
        if not PII.search(col) or SKIP.search(col): continue
        v = vals[idx].strip()
        if not (v.startswith("'") and v.endswith("'")): continue  # only quoted strings
        _, cl, _ = meta.get((tbl, col), ("VARCHAR2", 60, "Y"))
        fk = Faker(); fk.seed_instance(int(hashlib.md5((col + "|" + v).encode()).hexdigest(), 16) % (10**9))
        vals[idx] = fake_for(col, fk, max(4, min(cl, 80)))
    return "Insert into {} ({}) values ({});\n".format(tbl, m.group(2), ",".join(vals))

files = ins = faked = 0
for path in sorted(glob.glob(os.path.join(SEEDDIR, "*.sql"))):
    out = []
    for line in open(path):
        if line.startswith("Insert into"):
            ins += 1
            new = process(line)
            if new != line: faked += 1
            out.append(new)
        else:
            out.append(line)
    open(os.path.join(OUTDIR, os.path.basename(path)), "w").writelines(out)
    files += 1
print(f"files={files} inserts={ins} faked_inserts={faked} -> {OUTDIR}")
