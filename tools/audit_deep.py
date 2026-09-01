#!/usr/bin/env python3
"""KLC v1.0 DEEP audit: FXML <-> controller <-> schema integrity.

Checks beyond audit_fxml.py:
  1. fx:id Java field TYPE vs FXML element tag (ClassCastException at load)
  2. onXxx="#handler" methods must be public OR @FXML-annotated
  3. fx:include source paths must resolve (relative to including file)
  4. duplicate fx:ids inside one file
  5. controllers with @FXML fields never present in any FXML (info only)
"""
import re, os, glob, sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SRC = os.path.join(ROOT, "src", "main")
FXML_DIR = os.path.join(SRC, "resources", "fxml")
JAVA_DIR = os.path.join(SRC, "java")

def read(p):
    with open(p, encoding="utf-8", errors="replace") as f:
        return f.read()

def ctrl_path(cls):
    return os.path.join(JAVA_DIR, *cls.split(".")) + ".java"

# FXML element tag -> JavaFX field types that accept it
TAG_TYPES = {
    "Label": {"Label"},
    "TextField": {"TextField"},
    "PasswordField": {"PasswordField"},
    "TextArea": {"TextArea"},
    "Button": {"Button"},
    "Hyperlink": {"Hyperlink"},
    "CheckBox": {"CheckBox", "CheckBoxTreeCell"},
    "RadioButton": {"RadioButton"},
    "ToggleGroup": {"ToggleGroup"},
    "ComboBox": {"ComboBox"},
    "ChoiceBox": {"ChoiceBox"},
    "DatePicker": {"DatePicker"},
    "TableView": {"TableView"},
    "TableColumn": {"TableColumn"},
    "ListView": {"ListView"},
    "TreeView": {"TreeView"},
    "TreeTableView": {"TreeTableView"},
    "ImageView": {"ImageView"},
    "ProgressBar": {"ProgressBar"},
    "ProgressIndicator": {"ProgressIndicator", "ProgressBar"},
    "Slider": {"Slider"},
    "Spinner": {"Spinner"},
    "Tab": {"Tab"},
    "TabPane": {"TabPane"},
    "SplitPane": {"SplitPane"},
    "ScrollPane": {"ScrollPane"},
    "WebView": {"WebView"},
    "HTMLEditor": {"HTMLEditor"},
    "MenuBar": {"MenuBar"},
    "Menu": {"Menu"},
    "MenuItem": {"MenuItem"},
    "Separator": {"Separator"},
    "Pagination": {"Pagination"},
    "ColorPicker": {"ColorPicker"},
    "TextArea": {"TextArea"},
}
CONTAINER_TAGS = {  # any Parent/Pane/Region-typed field is fine
    "VBox", "HBox", "StackPane", "AnchorPane", "BorderPane", "GridPane",
    "FlowPane", "TilePane", "Pane", "Region", "Group", "Pane"
}

problems, infos = [], []
fxml_files = glob.glob(os.path.join(FXML_DIR, "**", "*.fxml"), recursive=True)
all_java = {}
for jf in glob.glob(os.path.join(JAVA_DIR, "**", "*.java"), recursive=True):
    all_java[jf] = read(jf)

for fxml in sorted(fxml_files):
    rel = os.path.relpath(fxml, SRC)
    content = read(fxml)
    m = re.search(r'fx:controller="([^"]+)"', content)
    if not m:
        continue
    cls = m.group(1)
    cp = ctrl_path(cls)
    if not os.path.exists(cp):
        continue  # already reported by audit_fxml
    jsrc = all_java.get(cp, read(cp))

    # ---- map of field name -> declared type in controller
    fields = {}
    for m2 in re.finditer(
            r'@FXML\s+((?:private|protected|public)\s+)?'
            r'([\w<>,\.\[\]\s]+?)\s+([\w\s,]+?)\s*(?:=|;)', jsrc, re.S):
        base_type = m2.group(2).strip()
        for name in m2.group(3).split(","):
            name = name.strip()
            if re.fullmatch(r'\w+', name):
                fields[name] = base_type

    # ---- fx:id occurrences with their element tag
    for tag_m in re.finditer(
            r'<(\w+)[^>]*?fx:id="([^"]+)"', content, re.S):
        tag, fid = tag_m.group(1), tag_m.group(2)
        if fid not in fields:
            continue  # missing-field case reported by audit_fxml
        jtype = fields[fid]
        base = jtype.split("<")[0].strip()
        if tag in CONTAINER_TAGS:
            continue
        expected = TAG_TYPES.get(tag)
        if expected and base not in expected:
            problems.append(
                f"[TYPE-MISMATCH] {rel}: fx:id '{fid}' on <{tag}> but "
                f"{cls}.{fid} is {jtype}")

    # ---- handlers: public or @FXML (robust declaration-line walk-back)
    def handler_ok(jsrc, name):
        pat = re.compile(r'\b' + re.escape(name) + r'\s*\(')
        for m in pat.finditer(jsrc):
            line_start = jsrc.rfind('\n', 0, m.start()) + 1
            before = jsrc[line_start:m.start()]
            # declaration lines carry modifiers/return type; call sites don't
            if not before.strip() or '.' in before:
                continue
            if not re.match(r'^\s*(?:(?:public|protected|private|static|final|synchronized)\s+)*[\w<>\[\],\s\.]*$',
                            before):
                continue
            mods = re.match(r'^\s*((?:public|protected|private|static|final|synchronized)\s+)*', before).group(0)
            is_public = 'public' in mods
            seg = jsrc[max(0, line_start - 120):line_start]
            last_end = max(seg.rfind(';'), seg.rfind('}'), seg.rfind('{'))
            has_fxml = '@FXML' in seg[last_end + 1:]
            return is_public or has_fxml
        return None  # not found (missing-method case handled elsewhere)

    for h in sorted(set(re.findall(r'\bon[A-Z]\w+="([^"]+)"', content))):
        name = h.lstrip("#")
        ok = handler_ok(jsrc, name)
        if ok is False:
            problems.append(
                f"[HANDLER-NOT-ACCESSIBLE] {rel}: #{name} in {cls} is "
                f"neither public nor @FXML")

    # ---- fx:include resolution
    for inc in re.finditer(r'<fx:include[^>]*?source="([^"]+)"', content):
        src = inc.group(1)
        if src.startswith("/"):
            p = os.path.join(SRC, "resources", src.lstrip("/"))
        else:
            p = os.path.normpath(os.path.join(os.path.dirname(fxml), src))
        if not os.path.exists(p):
            problems.append(f"[INCLUDE-MISSING] {rel}: fx:include {src}")

    # ---- duplicate fx:id in same file
    ids = re.findall(r'fx:id="([^"]+)"', content)
    for fid in set(ids):
        if ids.count(fid) > 1:
            problems.append(f"[DUP-FXID] {rel}: fx:id '{fid}' x{ids.count(fid)}")

# ---- controllers with @FXML fields never injected anywhere (NPE risk info)
all_fxml_text = "\n".join(read(f) for f in fxml_files)
for jf, jsrc in all_java.items():
    cm = re.search(r'public class (\w+)', jsrc)
    if not cm:
        continue
    cls = cm.group(1)
    relj = os.path.relpath(jf, SRC)
    if cls not in all_fxml_text and "@FXML" in jsrc:
        fxmls_of_cls = [os.path.relpath(f, SRC) for f in fxml_files
                        if cls in read(f)]
        if not fxmls_of_cls:
            n_fields = len(re.findall(r'@FXML', jsrc))
            infos.append(f"[ORPHAN-CONTROLLER] {relj}: class {cls} not "
                         f"referenced by any FXML ({n_fields} @FXML fields)")

print("=" * 72)
print(f"DEEP AUDIT - {len(fxml_files)} FXML / {len(all_java)} Java files")
print("=" * 72)
seen = set()
uniq = [p for p in problems if not (p in seen or seen.add(p))]
print(f"\nPROBLEMS ({len(uniq)}):")
for p in uniq: print("  x " + p)
print(f"\nINFO ({len(infos)}):")
for i in infos: print("  i " + i)
sys.exit(1 if problems else 0)
