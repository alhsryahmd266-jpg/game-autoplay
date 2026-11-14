"""Export the official Ultralytics YOLO11n (COCO) weights to TFLite + ONNX for use as a stand-in detector."""
import os, shutil, traceback

os.makedirs("out", exist_ok=True)
os.chdir("out")

from ultralytics import YOLO


def log(*a):
    print("[export]", *a, flush=True)


m = YOLO("yolo11n.pt")
log("classes:", len(m.names), "person =", m.names.get(0))

try:
    log("tflite ->", m.export(format="tflite", imgsz=640))
except Exception:
    traceback.print_exc()

try:
    log("onnx ->", m.export(format="onnx", imgsz=640, opset=12, simplify=True))
except Exception:
    traceback.print_exc()

import glob
found = glob.glob("**/*.tflite", recursive=True)
log("tflite files found:", found)
for f in found:
    low = f.lower()
    if "yolo11n-coco" in low:
        continue
    if "float16" in low:
        dst = "yolo11n-coco-f16.tflite"
    elif "float32" in low:
        dst = "yolo11n-coco-f32.tflite"
    else:
        dst = "yolo11n-coco.tflite"
    shutil.copy(f, dst)
    log("ready", dst, os.path.getsize(dst))
if os.path.exists("yolo11n.onnx"):
    shutil.copy("yolo11n.onnx", "yolo11n-coco.onnx")
    log("ready yolo11n-coco.onnx", os.path.getsize("yolo11n-coco.onnx"))

# ---- sanity checks (also tells us the real output layout of the exported model) ----
try:
    import urllib.request
    urllib.request.urlretrieve("https://ultralytics.com/images/bus.jpg", "bus.jpg")
except Exception as e:
    log("bus.jpg download failed:", e)

for f in sorted(glob.glob("yolo11n-coco*.tflite")) + ["yolo11n-coco.onnx"]:
    if os.path.exists(f) and os.path.exists("bus.jpg"):
        try:
            r = YOLO(f, task="detect")("bus.jpg", imgsz=640, verbose=False)[0]
            log("sanity", f, [r.names[int(c)] for c in r.boxes.cls])
        except Exception:
            traceback.print_exc()

try:
    import numpy as np
    from PIL import Image
    try:
        from ai_edge_litert.interpreter import Interpreter
    except Exception:
        import tensorflow as tf
        Interpreter = tf.lite.Interpreter
    tf_path = sorted(glob.glob("yolo11n-coco*.tflite"))[0]
    it = Interpreter(model_path=tf_path)
    it.allocate_tensors()
    i, o = it.get_input_details()[0], it.get_output_details()[0]
    log("tflite input", i["shape"].tolist(), i["dtype"].__name__, "output", o["shape"].tolist(), o["dtype"].__name__)
    im = Image.open("bus.jpg").convert("RGB").resize((640, 640))
    x = (np.asarray(im, dtype=np.float32) / 255.0)[None]
    it.set_tensor(i["index"], x)
    it.invoke()
    y = it.get_tensor(o["index"])[0]
    sc = y[4:].max(0)
    for k in sc.argsort()[-4:][::-1]:
        log("top anchor score", round(float(sc[k]), 3), "box", [round(float(v), 3) for v in y[:4, k]])
except Exception:
    traceback.print_exc()
