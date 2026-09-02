"""Run PP-DocLayoutV3 ONNX with the official post-processing semantics.

The generated overlays are deliberately host-side QA artifacts.  They let us reject a
layout model before its output is allowed to populate the Android document database.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw, ImageFont


LABELS = (
    "abstract", "algorithm", "aside_text", "chart", "content",
    "display_formula", "doc_title", "figure_title", "footer", "footer_image",
    "footnote", "formula_number", "header", "header_image", "image",
    "inline_formula", "number", "paragraph_title", "reference",
    "reference_content", "seal", "table", "text", "vertical_text",
    "vision_footnote",
)
COLORS = (
    "#EF5350", "#AB47BC", "#5C6BC0", "#29B6F6", "#26A69A",
    "#66BB6A", "#D4E157", "#FFCA28", "#FFA726", "#8D6E63",
    "#78909C", "#EC407A", "#7E57C2", "#42A5F5", "#26C6DA",
    "#9CCC65", "#FFEE58", "#FF7043", "#BDBDBD", "#8BC34A",
    "#FF9800", "#00ACC1", "#43A047", "#3949AB", "#F06292",
)


def sigmoid(values: np.ndarray) -> np.ndarray:
    return 1.0 / (1.0 + np.exp(-np.clip(values, -80.0, 80.0)))


def order_ranks(order_logits: np.ndarray) -> np.ndarray:
    """Port of PPDocLayoutV3ImageProcessor._get_order_seqs."""
    scores = sigmoid(order_logits)
    upper = np.triu(scores, k=1).sum(axis=0)
    lower = np.tril(1.0 - scores.T, k=-1).sum(axis=0)
    pointers = np.argsort(upper + lower)
    ranks = np.empty_like(pointers)
    ranks[pointers] = np.arange(len(pointers), dtype=pointers.dtype)
    return ranks


def infer(session: ort.InferenceSession, image: Image.Image, threshold: float):
    width, height = image.size
    resized = image.convert("RGB").resize((800, 800), Image.Resampling.BICUBIC)
    pixels = np.asarray(resized, dtype=np.float32).transpose(2, 0, 1)[None] / 255.0
    logits, boxes, masks, ordering = session.run(None, {"pixel_values": pixels})

    # Match the official processor: top 300 query/class pairs globally, then threshold.
    probabilities = sigmoid(logits[0]).reshape(-1)
    top = np.argpartition(probabilities, -300)[-300:]
    top = top[np.argsort(probabilities[top])[::-1]]
    scores = probabilities[top]
    labels = top % len(LABELS)
    queries = top // len(LABELS)
    keep = scores >= threshold
    scores, labels, queries = scores[keep], labels[keep], queries[keep]

    ranks = order_ranks(ordering[0])[queries]
    sequence = np.argsort(ranks)
    scores, labels, queries, ranks = (
        scores[sequence], labels[sequence], queries[sequence], ranks[sequence]
    )

    selected_boxes = boxes[0][queries]
    centers, dimensions = selected_boxes[:, :2], selected_boxes[:, 2:]
    xyxy = np.concatenate((centers - dimensions / 2, centers + dimensions / 2), axis=1)
    xyxy *= np.array([width, height, width, height], dtype=np.float32)
    selected_masks = sigmoid(masks[0][queries]) >= threshold
    return scores, labels, xyxy, selected_masks, ranks


def overlay(image: Image.Image, result, destination: Path) -> None:
    scores, labels, boxes, masks, ranks = result
    rendered = image.convert("RGBA")
    mask_layer = Image.new("RGBA", rendered.size, (0, 0, 0, 0))
    font = ImageFont.load_default()
    for label, box, mask in zip(labels, boxes, masks):
        color = COLORS[int(label)]
        alpha = Image.new("L", (800, 800), 0)
        alpha.putdata((mask.astype(np.uint8) * 45).reshape(-1).tolist())
        alpha = alpha.resize(rendered.size, Image.Resampling.NEAREST)
        fill = Image.new("RGBA", rendered.size, color)
        mask_layer.alpha_composite(Image.composite(fill, Image.new("RGBA", rendered.size), alpha))
    rendered = Image.alpha_composite(rendered, mask_layer)
    draw = ImageDraw.Draw(rendered)
    for score, label, box, rank in zip(scores, labels, boxes, ranks):
        x0, y0, x1, y1 = box.tolist()
        color = COLORS[int(label)]
        draw.rectangle((x0, y0, x1, y1), outline=color, width=4)
        text = f"{int(rank):03d} {LABELS[int(label)]} {float(score):.2f}"
        left = max(0, x0)
        top = max(0, y0 - 15)
        text_box = draw.textbbox((left, top), text, font=font)
        draw.rectangle(text_box, fill="#101010")
        draw.text((left, top), text, fill=color, font=font)
    rendered.convert("RGB").save(destination, quality=95)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("images", nargs="+", type=Path)
    parser.add_argument(
        "--model",
        type=Path,
        default=Path("app/src/main/assets/models/pp_doclayout_v3_fp32.onnx"),
    )
    parser.add_argument("--threshold", type=float, default=0.5)
    args = parser.parse_args()
    session = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])
    print("inputs", [(x.name, x.shape, x.type) for x in session.get_inputs()])
    print("outputs", [(x.name, x.shape, x.type) for x in session.get_outputs()])
    for path in args.images:
        image = Image.open(path)
        result = infer(session, image, args.threshold)
        destination = path.with_name(f"{path.stem}-layout-v3.jpg")
        overlay(image, result, destination)
        scores, labels, boxes, _, ranks = result
        print(f"\n{path} -> {destination}")
        for score, label, box, rank in zip(scores, labels, boxes, ranks):
            print(f"  {int(rank):3d} {LABELS[int(label)]:18s} {float(score):.3f} {box.round(1).tolist()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
