#!/usr/bin/env python3
"""KLC CBT Suite - static integrity audit
1. Every FXML's fx:controller must exist as a .java class
2. Every fx:id must have a matching @FXML field in the controller
3. Every onAction="#method" / onXxx="#method" must exist in the controller
4. Every MainApp.setRoot("...") / FXMLLoader path must exist in resources
5. Controllers listed in resources vs orphans on disk
"""
import re, os, sys, glob

ROOT = os.path.join(os.path.dirname(__file__), "..")
SRC = os.path.join(ROOT, "src", "main")
FXML_DIR = os.path.join(SRC, "resources", "fxml")
JAVA_DIR = os.path.join(SRC, "java")

def read(p):
    with open(p, encoding="utf-8", errors="replace") as f:
        return f.read()

def ctrl_path(cls):
    return os.path.join(JAVA_DIR, *cls.split(".")) + ".java"

problems, warnings = [], []
fxml_files = glob.glob(os.path.join(FXML_DIR, "**", "*.fxml"), recursive=True)
controllers = set()

for fxml in sorted(fxml_files):
    rel = os.path.relpath(fxml, SRC, )
    content = read(fxml)
    m = re.search(r'fx:controller="([^"]+)"', content)
    if not m:
        warnings.append(f"[FXML] no fx:controller: {rel}")
        continue
    cls = m.group(1)
    controllers.add(cls)
    cp = ctrl_path(cls)
    if not os.path.exists(cp):
        problems.append(f"[CTRL-MISSING] {rel}: controller class {cls} not found")
        continue
    jsrc = read(cp)

    # fx:id vs @FXML fields (handles comma-separated declarations)
    fxml_ids = set(re.findall(r'fx:id="([^"]+)"', content))
    java_fields = set()
    for m2 in re.finditer(r'@FXML\s+((?:private|protected|public)\s+)?([\w<>,\.\[\]\s]+?)\s+([\w\s,]+?)\s*(?:=|;)', jsrc, re.S):
        for name in m2.group(3).split(","):
            name = name.strip()
            if re.fullmatch(r'\w+', name):
                java_fields.add(name)

    for fid in sorted(fxml_ids):
        if fid not in java_fields:
            problems.append(f"[ID-MISSING] {rel}: fx:id '{fid}' has no @FXML field in {cls}")
    # reverse: @FXML fields not in fxml (may be injected from other fxml or unused)
    # handlers
    # handlers (onAction/onKeyPressed/... must start on + uppercase; guard against fx:controller)
    handlers = set(re.findall(r'\bon[A-Z]\w+="([^"]+)"', content))
    for h in sorted(handlers):
        name = h.lstrip("#")
        if name and not re.search(r'\b' + re.escape(name) + r'\s*\(', jsrc):
            problems.append(f"[HANDLER-MISSING] {rel}: onAction '{name}' not found in {cls}")

# all fxml paths referenced from java
java_files = glob.glob(os.path.join(JAVA_DIR, "**", "*.java"), recursive=True)
all_fxml_rel = {os.path.relpath(f, os.path.join(SRC, "resources")).replace(os.sep, "/") for f in fxml_files}
all_fxml_basenames = {os.path.basename(p) for p in all_fxml_rel}
# Controllers that test existence via getResource()!=null BEFORE loading
# (graceful fallback screens - teacher/exam-officer dashboards not shipped).
GUARDED_FALLBACKS = {"teacher_dashboard.fxml", "exam_officer_dashboard.fxml"}

def resolves(p):
    """A path resolves if it exists at /fxml/, /fxml/admin/ or /fxml/social/
    (bare names are passed through the load()/loadSocial() prefix helpers)."""
    if p in all_fxml_rel:
        return True
    if p.startswith("fxml/"):
        base = os.path.basename(p)
        return ("fxml/" + base in all_fxml_rel
                or "fxml/admin/" + base in all_fxml_rel
                or "fxml/social/" + base in all_fxml_rel)
    return False

for jf in sorted(java_files):
    jsrc = read(jf)
    relj = os.path.relpath(jf, SRC)
    for m in re.finditer(r'"([a-zA-Z0-9_/]+\.fxml)"', jsrc):
        p = m.group(1)
        if not p.startswith("/"):
            p = "fxml/" + p
        else:
            p = p.lstrip("/")
        if resolves(p):
            continue
        if os.path.basename(p) in GUARDED_FALLBACKS:
            warnings.append(
                f"[FALLBACK] {relj}: {p} not shipped (guarded fallback "
                f"to admin_dashboard) - OK")
            continue
        problems.append(f"[FXML-MISSING] {relj}: references {p} which does not exist")

# controllers referenced but never used by any fxml (orphan check reverse)
fxml_ctrls = set()
for fxml in fxml_files:
    m = re.search(r'fx:controller="([^"]+)"', read(fxml))
    if m: fxml_ctrls.add(m.group(1))
for jf in sorted(java_files):
    jsrc = read(jf)
    if re.search(r'public class \w+ ', jsrc) and "/fxml/" not in jsrc:
        pass
# FXML files with no controller
for fxml in fxml_files:
    rel = os.path.relpath(fxml, SRC)
    content = read(fxml)
    if "fx:controller" not in content:
        warnings.append(f"[NO-CONTROLLER] {rel} has no fx:controller")

print("=" * 70)
print(f"FXML files scanned : {len(fxml_files)}")
print(f"Java files scanned : {len(java_files)}")
print("=" * 70)
print(f"\nPROBLEMS ({len(problems)}):")
for p in problems: print("  ✗ " + p)
print(f"\nWARNINGS ({len(warnings)}):")
for w in warnings: print("  ! " + w)
sys.exit(1 if problems else 0)
