# Android Demo

## Features

- Login and register
- Bottom tabs: `record` / `insight` / `calendar` / `account`
- Record audio and upload for ASR + SER result
- Insight charts and diary calendar view

## Run

1. Start backend first (default `http://10.0.2.2:8000` for emulator).
2. Open `android-demo` in Android Studio.
3. Run on emulator or device.

For real device testing, change `BASE_URL` in `RetrofitClient.kt` to your LAN IP,
for example: `http://192.168.1.8:8000/`.

## Bundled SER Model

- SER weight is bundled at:
  - `app/src/main/assets/models/ser_model_seed2022.pth`
- This prepares the app for future on-device SER inference.
- To export a mobile TorchScript model:
  - `python android-demo/tools/export_ser_torchscript.py`
  - Output: `app/src/main/assets/models/ser_model_seed2022.ptl`
