# Android Demo

## Features

- Login and register
- Bottom tabs: `record` / `insight` / `calendar` / `account`
- Record audio and upload for ASR + SER result
- Insight charts and diary calendar view

## Run

1. Start backend first (current server: `http://8.136.19.253/`).
2. Open `android-demo` in Android Studio.
3. Run on emulator or device.

For real device testing, the app currently points to the deployed backend:
`http://8.136.19.253/`.

## Bundled SER Model

- SER weight is bundled at:
  - `app/src/main/assets/models/ser_model_seed2022.pth`
- This prepares the app for future on-device SER inference.
- To export a mobile TorchScript model:
  - `python android-demo/tools/export_ser_torchscript.py`
  - Output: `app/src/main/assets/models/ser_model_seed2022.ptl`
