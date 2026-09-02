"""Exercise the same PP-DocLayoutV3 -> Pix2Text MFR cascade used by Android."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw

from verify_doclayout_v3 import LABELS, infer


ROOT = Path(__file__).resolve().parents[1]
MODELS = ROOT / "app" / "src" / "main" / "assets" / "models"
FORMULA_LABELS = {5, 15}


def byte_decoder() -> dict[str, int]:
    values = list(range(ord("!"), ord("~") + 1))
    values += list(range(ord("¡"), ord("¬") + 1))
    values += list(range(ord("®"), ord("ÿ") + 1))
    characters = list(values)
    extra = 0
    for value in range(256):
        if value not in values:
            values.append(value)
            characters.append(256 + extra)
            extra += 1
    return {chr(character): value for character, value in zip(characters, values)}


class FormulaRecognizer:
    def __init__(self) -> None:
        self.encoder = ort.InferenceSession(
            MODELS / "pix2text_mfr_encoder_1_5.onnx", providers=["CPUExecutionProvider"]
        )
        self.decoder = ort.InferenceSession(
            MODELS / "pix2text_mfr_decoder_1_5.onnx", providers=["CPUExecutionProvider"]
        )
        tokenizer = json.loads(
            (MODELS / "pix2text_mfr_tokenizer_1_5.json").read_text(encoding="utf-8")
        )
        self.vocabulary = {
            identifier: token for token, identifier in tokenizer["model"]["vocab"].items()
        }
        self.bytes = byte_decoder()

    def recognize(self, image: Image.Image) -> tuple[str, float, bool]:
        resized = image.convert("RGB").resize((384, 384), Image.Resampling.BICUBIC)
        pixels = np.asarray(resized, dtype=np.float32).transpose(2, 0, 1)[None] / 127.5 - 1.0
        hidden = self.encoder.run(None, {"pixel_values": pixels})[0]
        tokens = [1]
        probabilities: list[float] = []
        terminated = False
        for _ in range(256):
            logits = self.decoder.run(
                None,
                {
                    "input_ids": np.asarray([tokens], dtype=np.int64),
                    "encoder_hidden_states": hidden,
                },
            )[0][0, -1]
            token_id = int(logits.argmax())
            shifted = logits - logits.max()
            probabilities.append(float(np.exp(shifted[token_id]) / np.exp(shifted).sum()))
            if token_id == 2:
                terminated = True
                break
            tokens.append(token_id)
        encoded = "".join(self.vocabulary[token] for token in tokens if token > 4)
        text = bytes(self.bytes[character] for character in encoded).decode("utf-8", errors="replace").strip()
        confidence = (
            float(np.exp(np.log(np.maximum(probabilities, 1e-8)).mean()))
            if probabilities
            else 0.0
        )
        return text, confidence, terminated


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("images", nargs="+", type=Path)
    parser.add_argument("--threshold", type=float, default=0.5)
    args = parser.parse_args()
    layout = ort.InferenceSession(
        MODELS / "pp_doclayout_v3_fp32.onnx", providers=["CPUExecutionProvider"]
    )
    recognizer = FormulaRecognizer()
    complete = True
    for path in args.images:
        image = Image.open(path).convert("RGB")
        scores, labels, boxes, _, _ = infer(layout, image, args.threshold)
        overlay = image.copy()
        draw = ImageDraw.Draw(overlay)
        formulas = [
            (score, int(label), box)
            for score, label, box in zip(scores, labels, boxes)
            if int(label) in FORMULA_LABELS
        ]
        print(f"\n{path}: formulas={len(formulas)}")
        for index, (score, label, box) in enumerate(formulas):
            left, top, right, bottom = box.tolist()
            formula_height = bottom - top
            inline = LABELS[label] == "inline_formula"
            horizontal_margin = max(2, round(formula_height * (0.04 if inline else 0.10)))
            vertical_margin = max(2, round(formula_height * (0.08 if inline else 0.10)))
            crop = image.crop(
                (
                    max(0, round(left) - horizontal_margin),
                    max(0, round(top) - vertical_margin),
                    min(image.width, round(right) + horizontal_margin),
                    min(image.height, round(bottom) + vertical_margin),
                )
            )
            latex, confidence, terminated = recognizer.recognize(crop)
            complete = complete and terminated and bool(latex)
            print(
                f"  {index:02d} {LABELS[label]:15s} layout={float(score):.3f} "
                f"mfr={confidence:.3f} eos={terminated} {latex}"
            )
            draw.rectangle((left, top, right, bottom), outline="red", width=3)
        overlay.save(path.with_name(f"{path.stem}-hybrid-formulas.jpg"), quality=92)
    print("PASS" if complete else "FAIL")
    return 0 if complete else 1


if __name__ == "__main__":
    raise SystemExit(main())
