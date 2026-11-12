# Game AutoPlay
Android app that plays **your own offline game** through a floating ▶/■ button.
Screen capture (MediaProjection) → on-device TFLite detector → rule-based Brain → AccessibilityService gestures. No root, no injection, no external services.

## Use your trained model
Put `model.tflite` (YOLOv8, input 320) and `labels.txt` (one class per line) in `app/src/main/assets/`, then push — GitHub Actions rebuilds the APK.
Labels the Brain understands: `enemy, weapon, medkit, low_health, zone_edge` and UI controls `joystick, fire, heal, jump` (auto-calibration).
Export: `yolo export model=best.pt format=tflite imgsz=320`
Without a model the app just patrols (moves forward and sweeps the camera).
