"""Smoke-test the bundled WtP model with its official character-hash preprocessing."""

import json
from pathlib import Path
import sys

import numpy as np
import onnxruntime as ort


MODEL = Path(__file__).parents[1] / "app/src/main/assets/models/wtp_bert_mini.onnx"
PRIMES = np.array([31, 43, 59, 61, 73, 97, 103, 113], dtype=np.int64)
THRESHOLD = 0.01
MIXTURES = Path(__file__).parents[1] / "tmp/wtp-mixture-extract"


def logits(session: ort.InferenceSession, text: str) -> np.ndarray:
    codepoints = np.array([ord(character) for character in text], dtype=np.int64)
    hashed_ids = ((codepoints[:, None] + 1) * PRIMES[None, :]) % 8192
    return session.run(
        ["logits"],
        {
            "attention_mask": np.ones((1, len(text)), dtype=np.float16),
            "hashed_ids": hashed_ids[None, :, :],
        },
    )[0][0].astype(np.float32)


def adapter(style: str) -> tuple[np.ndarray, float, float]:
    schema = json.loads((MIXTURES / "schema.json").read_text())
    values = schema["content"]["en"]["content"][style]["content"]
    classifier = values[0]["content"]["content"]
    coefficient = np.load(MIXTURES / classifier["coef_"]["file"])[0]
    intercept = float(np.load(MIXTURES / classifier["intercept_"]["file"])[0])
    threshold = float(np.load(MIXTURES / values[2]["file"]))
    return coefficient, intercept, threshold


def main() -> int:
    session = ort.InferenceSession(MODEL, providers=["CPUExecutionProvider"])
    print("inputs", [(item.name, item.type, item.shape) for item in session.get_inputs()])
    print("outputs", [(item.name, item.type, item.shape) for item in session.get_outputs()])
    examples = [
        "This is people (only paper OC 173.4.2 said that). The next sentence.",
        "As shown in Fig. 2, the method by J. Smith is stable. A new result follows.",
        'Acceleration and gravitation [Fock (1959), p. 208]. A state follows.',
        (
            "Langevin took the particular value which makes transformation (11) an absolute "
            "time Galilean-type rotation FORMULA The calculated value of delta tau is then "
            "the same for stationary and for moving observers FORMULA If we take, for the "
            "time dilation, the well-established expression FORMULA"
        ),
        (
            "By integrating over clockwise and counterclockwise beams, respectively, and "
            "subsequently subtracting one obtains FORMULA Equation (29) is a recasting of the "
            "first-order Lorentz transformation into polar coordinates after which it is applied"
        ),
        (
            "Langevin took the particular value which makes transformation (11) an absolute "
            "time Galilean-type rotation equation (1). The calculated value of delta tau is "
            "then the same for stationary and for moving observers equation (1) If we take, "
            "for the time dilation, the well-established expression equation (1)"
        ),
        (
            "By integrating over clockwise and counterclockwise beams, respectively, and "
            "subsequently subtracting one obtains equation (1) Equation (29) is a recasting "
            "of the first-order Lorentz transformation into polar coordinates after which it is applied"
        ),
        "The transformation equation (1) converts Eq. (10) into the rotating frame.",
    ]
    for text in examples:
        all_logits = logits(session, text)
        print("text", text)
        for style in ("ersatz", "opus100", "ud"):
            coefficient, intercept, threshold = adapter(style)
            scores = 1 / (1 + np.exp(-(all_logits @ coefficient + intercept)))
            boundaries = [index for index, score in enumerate(scores) if score > threshold]
            print("style", style, "threshold", threshold)
            print("boundaries", [(index, text[index], round(float(scores[index]), 4)) for index in boundaries])
            markers = [index + len("equation (1)") - 1 for index in range(len(text)) if text.startswith("equation (1)", index)]
            print("equation probes", [(index, text[index], round(float(scores[index]), 4)) for index in markers])
            start = 0
            sentences = []
            for index in boundaries:
                sentences.append(text[start : index + 1].strip())
                start = index + 1
            if text[start:].strip():
                sentences.append(text[start:].strip())
            print("sentences", sentences)
    return 0


if __name__ == "__main__":
    sys.exit(main())
