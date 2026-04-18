#!/usr/bin/env python3
"""
NLLB-200 模型转换脚本
在你的本地电脑上运行（需要网络下载模型）

用法:
  pip install transformers torch onnx onnxruntime optimum
  python convert_model.py

输出: nllb-200-distilled-600M-quantized.onnx + tokenizer.json
"""

import os
import sys
import json
import shutil

MODEL_NAME = "facebook/nllb-200-distilled-600M"
OUTPUT_DIR = "./model-output"

def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    print("=" * 60)
    print("Step 1: Downloading model (~1.2GB)...")
    print("=" * 60)

    from transformers import AutoTokenizer, AutoModelForSeq2SeqLM

    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME, src_lang="zho_Hans")
    model = AutoModelForSeq2SeqLM.from_pretrained(MODEL_NAME)

    print(f"Model loaded: {sum(p.numel() for p in model.parameters())/1e6:.0f}M parameters")

    # 保存 tokenizer.json
    tokenizer.save_pretrained(os.path.join(OUTPUT_DIR, "temp_tokenizer"))
    shutil.copy(
        os.path.join(OUTPUT_DIR, "temp_tokenizer", "tokenizer.json"),
        os.path.join(OUTPUT_DIR, "tokenizer.json")
    )
    shutil.rmtree(os.path.join(OUTPUT_DIR, "temp_tokenizer"))
    print("Tokenizer saved: tokenizer.json")

    print("\n" + "=" * 60)
    print("Step 2: Exporting to ONNX...")
    print("=" * 60)

    from optimum.onnxruntime import ORTModelForSeq2SeqLM

    # 使用 optimum 自动导出
    ort_model = ORTModelForSeq2SeqLM.from_pretrained(MODEL_NAME, export=True)
    
    # 保存临时 ONNX 文件
    temp_dir = os.path.join(OUTPUT_DIR, "temp_onnx")
    ort_model.save_pretrained(temp_dir)

    print("ONNX export done")

    print("\n" + "=" * 60)
    print("Step 3: Quantizing to INT8...")
    print("=" * 60)

    from optimum.onnxruntime import ORTQuantizer
    from optimum.onnxruntime.configuration import AutoQuantizationConfig

    quantized_dir = os.path.join(OUTPUT_DIR, "quantized")
    os.makedirs(quantized_dir, exist_ok=True)

    # 量化每个 ONNX 文件
    onnx_files = [f for f in os.listdir(temp_dir) if f.endswith(".onnx")]

    for onnx_file in onnx_files:
        print(f"  Quantizing {onnx_file}...")
        try:
            quantizer = ORTQuantizer.from_pretrained(temp_dir, file_name=onnx_file)
            qconfig = AutoQuantizationConfig.avx512_vnni(is_static=False, per_channel=True)
            quantizer.quantize(save_dir=quantized_dir, quantization_config=qconfig)
            print(f"  ✓ {onnx_file} quantized")
        except Exception as e:
            print(f"  ✗ {onnx_file} quantization failed: {e}")
            # 保留原始文件
            shutil.copy(
                os.path.join(temp_dir, onnx_file),
                os.path.join(quantized_dir, onnx_file)
            )

    # 如果只有一个主要模型文件，重命名为标准名称
    encoder_file = None
    decoder_file = None
    for f in os.listdir(quantized_dir):
        if f.endswith(".onnx"):
            if "encoder" in f.lower():
                encoder_file = f
            elif "decoder" in f.lower():
                decoder_file = f

    # 合并为单文件（如果可能）或使用主模型文件
    main_onnx = None
    for f in os.listdir(quantized_dir):
        if f.endswith(".onnx") and "decoder" not in f.lower() and "encoder" not in f.lower():
            main_onnx = f
            break
    
    if main_onnx is None:
        # 找最大的文件
        max_size = 0
        for f in os.listdir(quantized_dir):
            fp = os.path.join(quantized_dir, f)
            if f.endswith(".onnx") and os.path.getsize(fp) > max_size:
                max_size = os.path.getsize(fp)
                main_onnx = f

    if main_onnx:
        final_path = os.path.join(OUTPUT_DIR, "nllb-200-distilled-600M-quantized.onnx")
        shutil.copy(os.path.join(quantized_dir, main_onnx), final_path)
        print(f"\nMain model: {main_onnx}")

    # 复制所有必要的 ONNX 文件
    for f in os.listdir(quantized_dir):
        if f.endswith(".onnx"):
            src = os.path.join(quantized_dir, f)
            dst = os.path.join(OUTPUT_DIR, f)
            if not os.path.exists(dst):
                shutil.copy(src, dst)

    # 清理
    shutil.rmtree(temp_dir, ignore_errors=True)
    shutil.rmtree(quantized_dir, ignore_errors=True)

    print("\n" + "=" * 60)
    print("Step 4: Final output")
    print("=" * 60)

    print(f"\nOutput files in {OUTPUT_DIR}/:")
    total_size = 0
    for f in sorted(os.listdir(OUTPUT_DIR)):
        fp = os.path.join(OUTPUT_DIR, f)
        if os.path.isfile(fp):
            size = os.path.getsize(fp)
            total_size += size
            print(f"  {f}: {size/1024/1024:.1f} MB")

    print(f"\nTotal: {total_size/1024/1024:.0f} MB")
    print("\n下一步:")
    print(f"1. 把 {OUTPUT_DIR}/ 下的 .onnx 和 tokenizer.json 上传到 GitHub Releases")
    print("2. Release tag: model")
    print("3. Android APP 会自动从 Releases 下载模型")

if __name__ == "__main__":
    main()
