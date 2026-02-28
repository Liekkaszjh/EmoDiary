import argparse
from pathlib import Path

import torch


def _replace_last_linear_to_6(model: torch.nn.Module) -> None:
    last_name = None
    last_layer = None
    for name, module in model.named_modules():
        if isinstance(module, torch.nn.Linear):
            last_name = name
            last_layer = module
    if last_layer is None or last_name is None:
        return
    if last_layer.out_features == 6:
        return
    parts = last_name.split(".")
    parent = model
    for p in parts[:-1]:
        parent = getattr(parent, p)
    setattr(parent, parts[-1], torch.nn.Linear(last_layer.in_features, 6))


def export_torchscript(ckpt_path: Path, out_path: Path) -> None:
    repo_root = Path(__file__).resolve().parents[2]
    import sys

    if str(repo_root) not in sys.path:
        sys.path.insert(0, str(repo_root))
    import models  # type: ignore

    model = models.CTMAM_EMODB(shape=(26, 57))
    _replace_last_linear_to_6(model)

    state_dict = torch.load(ckpt_path, map_location="cpu")
    model.load_state_dict(state_dict, strict=False)
    model.eval()

    sample = torch.randn(1, 1, 26, 57)
    traced = torch.jit.trace(model, sample, strict=False)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    try:
        from torch.utils.mobile_optimizer import optimize_for_mobile

        optimized = optimize_for_mobile(traced)
        optimized._save_for_lite_interpreter(str(out_path))
    except Exception:
        traced.save(str(out_path))


def main() -> None:
    parser = argparse.ArgumentParser(description="Export CTMAM SER checkpoint to TorchScript (.ptl)")
    parser.add_argument(
        "--ckpt",
        default="data/CASIA/model_CASIA_CTMAM_EMODB_mfcc_CASIA_random80_20_seed2022_hop512.pth",
        help="Path to .pth checkpoint",
    )
    parser.add_argument(
        "--out",
        default="android-demo/app/src/main/assets/models/ser_model_seed2022.ptl",
        help="Output TorchScript path",
    )
    args = parser.parse_args()

    ckpt_path = Path(args.ckpt).resolve()
    out_path = Path(args.out).resolve()
    if not ckpt_path.exists():
        raise FileNotFoundError(f"Checkpoint not found: {ckpt_path}")

    export_torchscript(ckpt_path, out_path)
    print(f"[OK] exported: {out_path}")


if __name__ == "__main__":
    main()
