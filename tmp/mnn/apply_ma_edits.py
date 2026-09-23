#!/usr/bin/env python3
# NOVA-MNN engine swap: applies the ModelsActivity edits (MNN model dirs,
# dir-aware sizes, recursive delete). Anchors must match exactly once.
import hashlib, sys

MA = "android/app/src/main/java/org/nova/ModelsActivity.kt"

EDITS = [
    ('val models = dir.listFiles { f: File -> f.name.endsWith(".gguf") }',
     'val models = dir.listFiles { f: File -> f.isDirectory && File(f, "config.json").exists() }'),
    ('text = "No models yet \u2014 download one above, or import a .gguf file.\\n\\n" +',
     'text = "No models yet \u2014 download one above (MNN models only).\\n\\n" +'),
    ('text = "${m.name} \u00b7 ${m.length() / (1000L * 1000 * 1000)} GB"',
     'text = "${m.name} \u00b7 ${ModelCatalog.sizeOf(m) / (1000L * 1000 * 1000)} GB"'),
    ('.setMessage("${f.name} (${f.length() / (1000L * 1000 * 1000)} GB) will be permanently removed.")',
     '.setMessage("${f.name} (${ModelCatalog.sizeOf(f) / (1000L * 1000 * 1000)} GB) will be permanently removed.")'),
    ('                        f.delete()',
     '                        if (f.isDirectory) f.deleteRecursively() else f.delete()'),
    ('val fit = DeviceCapabilities.fitFor(f.length(), this)',
     'val fit = DeviceCapabilities.fitFor(ModelCatalog.sizeOf(f), this)'),
    ('"(${f.length() / (1000L * 1000 * 1000)} GB file, " +',
     '"(${ModelCatalog.sizeOf(f) / (1000L * 1000 * 1000)} GB model, " +'),
]

def main():
    s = open(MA, encoding="utf-8").read()
    ok = True
    for old, new in EDITS:
        n = s.count(old)
        if n != 1:
            print(f"ANCHOR FAIL ({n}x): {old[:60]}")
            ok = False
            continue
        s = s.replace(old, new)
    if not ok:
        sys.exit(1)
    with open(MA, "w", encoding="utf-8") as f:
        f.write(s)
    print(f"edited {MA} (md5 {hashlib.md5(s.encode()).hexdigest()})")

if __name__ == "__main__":
    main()
