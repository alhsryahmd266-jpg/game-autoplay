"""Load every given .tflite with the TensorFlow 2.16 runtime (same family as the Android app) and run it on a sample frame."""
import sys, urllib.request
import numpy as np
from PIL import Image
import tensorflow as tf

print("tensorflow", tf.__version__, flush=True)
urllib.request.urlretrieve("https://ultralytics.com/images/bus.jpg", "bus.jpg")
bad = 0
for path in sys.argv[1:]:
    try:
        it = tf.lite.Interpreter(model_path=path)
        it.allocate_tensors()
        i, o = it.get_input_details()[0], it.get_output_details()[0]
        shape = [int(v) for v in i["shape"]]
        nchw = shape[1] == 3
        H, W = (shape[2], shape[3]) if nchw else (shape[1], shape[2])
        x = np.asarray(Image.open("bus.jpg").convert("RGB").resize((W, H)), dtype=np.float32) / 255.0
        x = (x.transpose(2, 0, 1) if nchw else x)[None]
        it.set_tensor(i["index"], x)
        it.invoke()
        y = it.get_tensor(o["index"])
        msg = "%s | in %s | out %s" % (path, shape, list(y.shape))
        y0 = y[0]
        if y0.ndim == 2 and y0.shape[1] == 6 and y0.shape[0] <= 1000:
            sel = y0[y0[:, 4] > 0.3]
            msg += " | end2end dets>0.3: %d | classes %s | max box value %.2f" % (len(sel), sorted({int(c) for c in sel[:, 5]}), float(sel[:, :4].max()) if len(sel) else -1)
        else:
            a = y0 if y0.shape[0] < y0.shape[1] else y0.T
            sc = a[4:].max(0)
            msg += " | classic head, anchors>0.3: %d | max box value %.2f" % (int((sc > 0.3).sum()), float(a[:4, sc > 0.3].max()) if (sc > 0.3).any() else -1)
        print("OK  ", msg, flush=True)
    except Exception as e:
        bad += 1
        print("FAIL", path, repr(e)[:400], flush=True)
print("RESULT:", "all files load and run on TF 2.16" if bad == 0 else "%d file(s) FAILED on TF 2.16" % bad, flush=True)
