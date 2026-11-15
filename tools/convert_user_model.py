"""Convert a user-trained Ultralytics detection checkpoint (.pt) to ONNX + TFLite for the AutoPlay app.
Variants: native square size and a 20:9 rectangle (288x640, no padding waste on a landscape phone)."""
import glob, json, os, shutil, sys, traceback, zipfile

src = os.path.abspath(sys.argv[1])
os.makedirs("out", exist_ok=True)
os.chdir("out")
shutil.copy(src, "model.pt")

import ultralytics
from ultralytics import YOLO


def log(*a):
    print("[convert]", *a, flush=True)


m = YOLO("model.pt")
names = {int(k): str(v) for k, v in m.names.items()}
targs = (getattr(m, "ckpt", None) or {}).get("train_args", {}) or {}
isz = targs.get("imgsz", 640)
isz = int(isz[0] if isinstance(isz, (list, tuple)) else isz)
log("ultralytics", ultralytics.__version__, "| task", m.task, "| names", names, "| train imgsz", isz)

with open("autoplay-names.txt", "w") as f:
    f.write("\n".join(names[i] for i in sorted(names)) + "\n")


def add_tflite_meta(path, h, w):
    meta = {"names": {str(k): v for k, v in names.items()}, "imgsz": [h, w], "task": "detect"}
    with zipfile.ZipFile(path, "a", zipfile.ZIP_STORED) as z:
        z.writestr("metadata.json", json.dumps(meta))


def export(fmt, size, tag):
    ext = "tflite" if fmt == "tflite" else "onnx"
    try:
        kw = {"simplify": True} if fmt == "onnx" else {}
        p = str(m.export(format=fmt, imgsz=size, **kw))
        if os.path.isdir(p):
            cands = glob.glob(os.path.join(p, "**", "*." + ext), recursive=True)
        else:
            cands = [p] if os.path.exists(p) else []
        if not cands:
            log("no", ext, "file found after export of", tag)
            return
        best = sorted(cands, key=lambda f: ("float16" in f, len(f)))[0]
        dst = "autoplay-%s.%s" % (tag, ext)
        shutil.copy(best, dst)
        h, w = (size, size) if isinstance(size, int) else (size[0], size[1])
        if fmt == "tflite":
            add_tflite_meta(dst, h, w)
        log("ready", dst, os.path.getsize(dst))
    except Exception:
        traceback.print_exc()
        log("FAILED", fmt, tag)


rect = [288, 640]
export("onnx", rect, "288x640")
export("onnx", isz, str(isz))
export("tflite", rect, "288x640")
export("tflite", isz, str(isz))

# ---- sanity: every produced file must load; print real I/O layout ----
try:
    import numpy as np
    import onnxruntime as ort
    for f in sorted(glob.glob("autoplay-*.onnx")):
        s = ort.InferenceSession(f)
        i, o = s.get_inputs()[0], s.get_outputs()[0]
        x = np.full([d if isinstance(d, int) else 1 for d in i.shape], 0.5, dtype=np.float32)
        y = s.run(None, {i.name: x})[0]
        log("onnx ok", f, "in", i.shape, "out", list(y.shape), "meta names:", (s.get_modelmeta().custom_metadata_map or {}).get("names"))
except Exception:
    traceback.print_exc()
try:
    try:
        from ai_edge_litert.interpreter import Interpreter
    except Exception:
        import tensorflow as tf
        Interpreter = tf.lite.Interpreter
    import numpy as np
    for f in sorted(glob.glob("autoplay-*.tflite")):
        it = Interpreter(model_path=f)
        it.allocate_tensors()
        i, o = it.get_input_details()[0], it.get_output_details()[0]
        it.set_tensor(i["index"], np.full(i["shape"], 0.5, dtype=np.float32))
        it.invoke()
        y = it.get_tensor(o["index"])
        log("tflite ok", f, "in", i["shape"].tolist(), "out", list(y.shape))
except Exception:
    traceback.print_exc()
log("files:", sorted(glob.glob("autoplay-*")))
