#!/usr/bin/env python3
# Fix: catalog size display one decimal (0 GB -> 0.5 GB)
import hashlib, sys

MA = "android/app/src/main/java/org/nova/ModelsActivity.kt"

EDITS = [
    ('text = "${entry.org} \u00b7 ${entry.quant} \u00b7 ${entry.sizeBytes / (1000L * 1000 * 1000)} GB \u00b7 " +',
     'text = "${entry.org} \u00b7 ${entry.quant} \u00b7 ${"%.1f".format(entry.sizeBytes / (1000.0 * 1000 * 1000))} GB \u00b7 " +'),
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
