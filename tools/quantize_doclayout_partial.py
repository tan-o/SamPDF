"""Create the conservative PP-DocLayoutV3 INT8 candidate.

Only constant MatMul weights are quantized. Convolutions and the classification,
instance-mask and reading-order paths remain FP32 until the visual regression gate passes.
"""

from pathlib import Path

from onnxruntime.quantization import QuantType, quantize_dynamic


ROOT = Path(__file__).parents[1]
SOURCE = ROOT / "tmp/PP-DocLayoutV3.onnx"
DESTINATION = ROOT / "tmp/pp_doclayout_v3_encoder_int8.onnx"
ENCODER_MATMULS = [
    "node_MatMul_2510",
    "node_MatMul_2518",
    "node_MatMul_2526",
    "node_MatMul_2562",
    "node_MatMul_2564",
    "node_MatMul_2573",
]


def main() -> None:
    quantize_dynamic(
        model_input=SOURCE,
        model_output=DESTINATION,
        nodes_to_quantize=ENCODER_MATMULS,
        op_types_to_quantize=["MatMul"],
        per_channel=True,
        reduce_range=False,
        weight_type=QuantType.QInt8,
        extra_options={"MatMulConstBOnly": True},
    )
    print(f"FP32: {SOURCE.stat().st_size} bytes")
    print(f"INT8: {DESTINATION.stat().st_size} bytes")


if __name__ == "__main__":
    main()
