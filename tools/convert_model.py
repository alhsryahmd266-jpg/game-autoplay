"""Generalised converter: Ultralytics detection checkpoint (.pt) -> ONNX + TFLite variants for the AutoPlay app.
usage: python convert_model.py <weights.pt> <prefix>
Outputs (in ./out):
  <prefix>-288x640.tflite       20:9 rectangle, model's default head (YOLO26: NMS-free [1,300,6])
  <prefix>-288x640-raw.tflite   same size, classic head [1,4+nc,N] (decode + NMS done in the app; GPU-delegate friendlier)
  <prefix>-<imgsz>.tflite       native square size
  matching .onnx files and <prefix>-names.txt
"""
import glob, json, os, shutil, sys, traceback, zipfile

src = os.path.abspath(sys.argv[1])
prefix = sys.argv[2]
os.makedirs("out", exist_ok=True)
os.chdir("out")
shutil.copy(src, "model.pt")

import ultralytics
from ultralytics import YOLO


def log(*a):
    print("[convert]", *a, flush=True)


m0 = YOLO("model.pt")
names = {int(k): str(v) for k, v in m0.names.items()}
targs = (getattr(m0, "ckpt", None) or {}).get("train_args", {}) or {}
isz = targs.get("imgsz", 640)
isz = int(isz[0] if isinstance(isz, (list, tuple)) else isz)
log("ultralytics", ultralytics.__version__, "| task", m0.task, "| classes", len(names), "| train imgsz", isz)
with open(prefix + "-names.txt", "w") as f:
    f.write("\n".join(names[i] for i in sorted(names)) + "\n")
del m0


def add_tflite_meta(path, h, w):
    meta = {"names": {str(k): v for k, v in names.items()}, "imgsz": [h, w], "task": "detect"}
    with zipfile.ZipFile(path, "a", zipfile.ZIP_STORED) as z:
        z.writestr("metadata.json", json.dumps(meta))


def clean():
    for p in glob.glob("model_*") + glob.glob("model.onnx") + glob.glob("model.tflite") + glob.glob("model_saved_model"):
        shutil.rmtree(p, ignore_errors=True) if os.path.isdir(p) else os.remove(p)


def export(fmt, size, tag, **extra):
    ext = "tflite" if fmt == "tflite" else "onnx"
    try:
        clean()
        m = YOLO("model.pt")  # fresh instance for every export
        kw = dict(extra)
        if fmt == "onnx":
            kw["simplify"] = True
        p = str(m.export(format=fmt, imgsz=size, **kw))
        if os.path.isdir(p):
            cands = glob.glob(os.path.join(p, "**", "*." + ext), recursive=True)
        else:
            cands = [p] if os.path.exists(p) else []
        if not cands:
            cands = glob.glob("**/*." + ext, recursive=True)
        cands = [c for c in cands if not os.path.basename(c).startswith(prefix)]
        if not cands:
            log("no", ext, "file found after export of", tag)
            return
        best = sorted(cands, key=lambda f: ("int8" in f, "float16" in f, len(f)))[0]
        dst = "%s-%s.%s" % (prefix, tag, ext)
        shutil.copy(best, dst)
        h, w = (size, size) if isinstance(size, int) else (size[0], size[1])
        if fmt == "tflite":
            add_tflite_meta(dst, h, w)
        log("ready", dst, os.path.getsize(dst), "<-", best)
    except Exception:
        traceback.print_exc()
        log("FAILED", fmt, tag)
    finally:
        clean()


rect = [288, 640]
export("tflite", rect, "288x640")
export("tflite", rect, "288x640-raw", end2end=False)
export("tflite", isz, str(isz))
export("onnx", rect, "288x640")
export("onnx", isz, str(isz))

try:
    import numpy as np
    import onnxruntime as ort
    for f in sorted(glob.glob(prefix + "-*.onnx")):
        s = ort.InferenceSession(f)
        i, o = s.get_inputs()[0], s.get_outputs()[0]
        x = np.full([d if isinstance(d, int) else 1 for d in i.shape], 0.5, dtype=np.float32)
        y = s.run(None, {i.name: x})[0]
        log("onnx ok", f, "in", i.shape, "out", list(y.shape))
except Exception:
    traceback.print_exc()
log("files:", sorted(glob.glob(prefix + "-*")))
