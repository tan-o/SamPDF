"""Compare a quantized layout candidate against the FP32 model on rendered pages."""

from __future__ import annotations

from pathlib import Path
import sys

import numpy as np
import onnxruntime as ort
from PIL import Image

from verify_doclayout_v3 import LABELS, infer


ROOT = Path(__file__).parents[1]
FP32 = ROOT / "tmp/PP-DocLayoutV3.onnx"
INT8 = ROOT / "tmp/pp_doclayout_v3_encoder_int8.onnx"


def box_iou(first: np.ndarray, second: np.ndarray) -> float:
    left, top = np.maximum(first[:2], second[:2])
    right, bottom = np.minimum(first[2:], second[2:])
    intersection = max(0.0, right - left) * max(0.0, bottom - top)
    area_a = max(0.0, first[2] - first[0]) * max(0.0, first[3] - first[1])
    area_b = max(0.0, second[2] - second[0]) * max(0.0, second[3] - second[1])
    union = area_a + area_b - intersection
    return 0.0 if union == 0 else float(intersection / union)


def compare(path: Path, fp_session, int_session) -> bool:
    image = Image.open(path)
    fp = infer(fp_session, image, 0.5)
    quant = infer(int_session, image, 0.5)
    fp_scores, fp_labels, fp_boxes, fp_masks, _ = fp
    q_scores, q_labels, q_boxes, q_masks, _ = quant
    available = set(range(len(q_labels)))
    matches: list[tuple[int, int, float]] = []
    for fp_index, (label, box) in enumerate(zip(fp_labels, fp_boxes)):
        options = [
            (box_iou(box, q_boxes[index]), index)
            for index in available
            if q_labels[index] == label
        ]
        if not options:
            continue
        overlap, q_index = max(options)
        if overlap >= 0.5:
            matches.append((fp_index, q_index, overlap))
            available.remove(q_index)

    mask_ious = []
    score_deltas = []
    for fp_index, q_index, _ in matches:
        intersection = np.logical_and(fp_masks[fp_index], q_masks[q_index]).sum()
        union = np.logical_or(fp_masks[fp_index], q_masks[q_index]).sum()
        mask_ious.append(1.0 if union == 0 else float(intersection / union))
        score_deltas.append(abs(float(fp_scores[fp_index] - q_scores[q_index])))
    quant_positions = [q_index for _, q_index, _ in sorted(matches)]
    pair_count = len(quant_positions) * (len(quant_positions) - 1) // 2
    inversions = sum(
        quant_positions[left] > quant_positions[right]
        for left in range(len(quant_positions))
        for right in range(left + 1, len(quant_positions))
    )
    order_agreement = 1.0 if pair_count == 0 else 1.0 - inversions / pair_count
    unmatched_fp = [LABELS[int(fp_labels[i])] for i in range(len(fp_labels)) if i not in {m[0] for m in matches}]
    unmatched_q = [LABELS[int(q_labels[i])] for i in available]
    print(path)
    print(f"  regions fp32={len(fp_labels)} int8={len(q_labels)} matched={len(matches)}")
    print(f"  box_iou mean={np.mean([m[2] for m in matches]):.6f}")
    print(f"  mask_iou mean={np.mean(mask_ious):.6f} min={np.min(mask_ious):.6f}")
    if mask_ious:
        worst = int(np.argmin(mask_ious))
        fp_index, q_index, _ = matches[worst]
        print(
            "  worst_mask="
            f"{LABELS[int(fp_labels[fp_index])]} "
            f"iou={mask_ious[worst]:.6f} "
            f"pixels={int(fp_masks[fp_index].sum())}/{int(q_masks[q_index].sum())}"
        )
    print(f"  score_abs_delta mean={np.mean(score_deltas):.6f} max={np.max(score_deltas):.6f}")
    print(f"  reading_order_pair_agreement={order_agreement:.6f}")
    print(f"  unmatched_fp32={unmatched_fp}")
    print(f"  unmatched_int8={unmatched_q}")
    return (
        len(matches) == len(fp_labels) == len(q_labels)
        and min(mask_ious, default=0.0) >= 0.97
        and order_agreement == 1.0
    )


def main(paths: list[str]) -> int:
    fp_session = ort.InferenceSession(FP32, providers=["CPUExecutionProvider"])
    int_session = ort.InferenceSession(INT8, providers=["CPUExecutionProvider"])
    results = [compare(Path(path), fp_session, int_session) for path in paths]
    passed = all(results)
    print("PASS" if passed else "FAIL")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
