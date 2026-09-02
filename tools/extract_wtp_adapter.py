"""Extract one trusted WtP sklearn adapter into a runtime-neutral JSON asset."""

import json
from pathlib import Path

import numpy as np


ROOT = Path(__file__).parents[1]
SOURCE = ROOT / "tmp/wtp-mixture-extract"
TARGET = ROOT / "app/src/main/assets/models/wtp_en_ersatz_adapter.json"


def main() -> None:
    schema = json.loads((SOURCE / "schema.json").read_text())
    values = schema["content"]["en"]["content"]["ersatz"]["content"]
    classifier = values[0]["content"]["content"]
    coefficients = np.load(SOURCE / classifier["coef_"]["file"])[0]
    intercept = float(np.load(SOURCE / classifier["intercept_"]["file"])[0])
    threshold = float(np.load(SOURCE / values[2]["file"]))
    payload = {
        "source": "benjamin/wtp-bert-mini mixtures.skops",
        "language": "en",
        "style": "ersatz",
        "coefficients": coefficients.tolist(),
        "intercept": intercept,
        "threshold": threshold,
    }
    TARGET.write_text(json.dumps(payload, separators=(",", ":")) + "\n")


if __name__ == "__main__":
    main()
