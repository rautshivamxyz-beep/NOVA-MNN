#!/usr/bin/env python3
# Import downloaded MNN folder: SAF tree import + custom-URL hint update
import hashlib, sys

MA = "android/app/src/main/java/org/nova/ModelsActivity.kt"

EDITS = [
    ("""        val importBtn = smallButton("Import local .gguf file", textDim)
        importBtn.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("*/*"))
            }
            startActivityForResult(intent, REQ_PICK_GGUF)
        }""",
     """        val importBtn = smallButton("Import downloaded MNN folder", textDim)
        importBtn.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_MNN_DIR)
        }"""),
    ('            text = "Any other GGUF (Hugging Face URL)"',
     '            text = "Any other model (Hugging Face URL)"'),
    ('            hint = "https://huggingface.co/.../model-Q4_K_M.gguf"',
     '            hint = "https://huggingface.co/taobao-mnn/Llama-3.2-1B-Instruct-MNN"'),
    ("""        if (requestCode == REQ_PICK_GGUF && resultCode == Activity.RESULT_OK) {
            data?.data?.let { importPicked(it) }
        }
    }""",
     """        if (requestCode == REQ_PICK_GGUF && resultCode == Activity.RESULT_OK) {
            data?.data?.let { importPicked(it) }
        }
        if (requestCode == REQ_PICK_MNN_DIR && resultCode == Activity.RESULT_OK) {
            data?.data?.let { importTreePicked(it) }
        }
    }"""),
    ("""                ModelDownloader.import(name, opener, size, ModelCatalog.modelsDir(this@ModelsActivity))
            }
        }
    }""",
     """                ModelDownloader.import(name, opener, size, ModelCatalog.modelsDir(this@ModelsActivity))
            }
        }
    }

    /** Picks a folder the user downloaded by hand and imports it as a model. */
    private fun importTreePicked(uri: Uri) {
        var folder = "imported-model-mnn"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.let { if (it.isNotBlank()) folder = it }
                }
            }
        } catch (e: Exception) {
            // keep the default folder name
        }
        toast("Importing $folder - keep this screen open")
        ModelDownloader.importTree(contentResolver, uri, folder, ModelCatalog.modelsDir(this))
    }"""),
    ('        private const val REQ_PICK_GGUF = 4242',
     '        private const val REQ_PICK_GGUF = 4242\n        private const val REQ_PICK_MNN_DIR = 4243'),
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
