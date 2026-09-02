PP-DocLayoutV3 model provenance

Official source: https://huggingface.co/PaddlePaddle/PP-DocLayoutV3
ONNX conversion: https://huggingface.co/beclab/PP-DocLayoutV3_onnx
License: Apache-2.0
Input: RGB 800x800; outputs: 25-class logits, boxes, 200x200 instance masks and reading order.
Packaged FP32 model SHA-256: B0DEEE066F8B71E6F8AE3A645C242F1985C4B66E6E3332E7D72CAE774A7F70AC
The previous partial-INT8 model is no longer packaged. On three representative scientific
paper pages it preserved region count and reading order, but the smallest inline-formula mask
fell to 0.956522 IoU against FP32 and score drift reached 0.012251. Precision is the product
priority, so the app ships exactly one full-FP32 layout model.

PaddleOCR repository and Apache-2.0 license:
https://github.com/PaddlePaddle/PaddleOCR

Pix2Text 1.5 mathematical formula models


MFR source: https://huggingface.co/breezedeus/pix2text-mfr-1.5
MFR architecture: DeiT encoder + TrOCR autoregressive decoder, ONNX opset 14
MFR license: MIT
pix2text_mfr_encoder_1_5.onnx SHA-256: 080A3F660F08BC9EBCACDD96E34BE6B6400F8C7E62D7CD0DD8251BADC37F610B
pix2text_mfr_decoder_1_5.onnx SHA-256: 917DEB98E91A0453C5F234F58A0F32F9FB037DE8527C7EB4ED394DAF9E692F2A
pix2text_mfr_tokenizer_1_5.json SHA-256: 4FFBEB2143E6A38324BB6111B7A8109530D38A076A8439AA5777535F0A32758A

WtP BERT mini sentence-boundary model

Source: https://huggingface.co/benjamin/wtp-bert-mini
Reference implementation: https://github.com/segment-any-text/wtpsplit
License: MIT
wtp_bert_mini.onnx SHA-256: 8F2E2ADA50239BEF37469E4187B209C70D141E02F193CDFB5436E2ED6070479A
English sentence adapter: official `en/ersatz` logistic mixture extracted from mixtures.skops
mixtures.skops SHA-256: 8C249F7E50C663E274AD069C4DBAC058B0244F00CD27A51AFA654D7D40353A9A
wtp_en_ersatz_adapter.json SHA-256: 5C22073296F4DE9AE1796F7D54F7D6C7F5FB478BA6D82B72ED0873A7432A975D
