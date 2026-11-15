"""Load the released .tflite with the TensorFlow 2.16 runtime (same family as the Android app) and print I/O + box scale."""
import sys, urllib.request
import numpy as np
from PIL import Image

urllib.request.urlretrieve(sys.argv[1], "m.tflite")
urllib.request.urlretrieve("https://ultralytics.com/images/bus.jpg", "bus.jpg")
import tensorflow as tf

print("tensorflow", tf.__version__, flush=True)
it = tf.lite.Interpreter(model_path="m.tflite")
it.allocate_tensors()
i, o = it.get_input_details()[0], it.get_output_details()[0]
print("input", i["shape"].tolist(), i["dtype"].__name__, "| output", o["shape"].tolist(), o["dtype"].__name__, flush=True)
shape = [int(v) for v in i["shape"]]
nchw = shape[1] == 3
H, W = (shape[2], shape[3]) if nchw else (shape[1], shape[2])
x = np.asarray(Image.open("bus.jpg").convert("RGB").resize((W, H)), dtype=np.float32) / 255.0
x = (x.transpose(2, 0, 1) if nchw else x)[None]
it.set_tensor(i["index"], x)
it.invoke()
y = it.get_tensor(o["index"])[0]
print("output tensor", y.shape, flush=True)
sc = y[4:].max(0)
for k in sc.argsort()[-5:][::-1]:
    print("anchor", int(k), "score", round(float(sc[k]), 3), "cls", int(y[4:, k].argmax()), "box", [round(float(v), 3) for v in y[:4, k]], flush=True)
sel = sc > 0.3
print("candidates >0.3:", int(sel.sum()), "| max box value:", float(y[:4, sel].max()) if sel.any() else None, flush=True)
print("RESULT: model loads and runs on the TF 2.16 runtime", flush=True)
