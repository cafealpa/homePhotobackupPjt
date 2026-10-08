"""Development parity check only. Runtime and model installation do not need Python.

Run with the previous search environment before removing it:
  python scripts/search-reference.py OLD_MODEL_DIR OUTPUT_JSON
Uses synthetic RGB images and invented queries; never accesses the photo library.
"""
import hashlib
import json
from pathlib import Path
import sys
import numpy as np
from PIL import Image
import torch
from transformers import AutoModel, AutoProcessor

torch.set_num_threads(2)
model = AutoModel.from_pretrained(sys.argv[1], local_files_only=True).eval()
processor = AutoProcessor.from_pretrained(sys.argv[1], local_files_only=True, use_fast=False)
result = {"images": [], "texts": []}
with torch.inference_mode():
    for width, height in [(317, 241), (63, 91), (400, 17), (224, 224)]:
        rgb = np.empty((height, width, 3), dtype=np.uint8)
        for y in range(height):
            for x in range(width):
                rgb[y, x] = [(x * 7 + y * 3) % 256, (x * 2 + y * 11) % 256, (x * 13 + y * 5) % 256]
        image = Image.fromarray(rgb)
        resized = image.resize((224, 224), Image.Resampling.BILINEAR)
        vec = model.get_image_features(**processor(images=image, return_tensors="pt"))[0].float().numpy()
        vec = vec / np.linalg.norm(vec)
        result["images"].append({"width": width, "height": height,
            "pixelsSha256": hashlib.sha256(resized.tobytes()).hexdigest(), "vector": vec.tolist()})
    for text in ["바닷가에서 노는 아이들", "A RED car!", "  여러  공백\n줄바꿈 😀", "a " * 100]:
        inputs = processor(text=[text], padding="max_length", truncation=True, max_length=64, return_tensors="pt")
        vec = model.get_text_features(**inputs)[0].float().numpy()
        result["texts"].append({"text": text, "tokens": inputs["input_ids"][0].tolist(),
            "vector": (vec / np.linalg.norm(vec)).tolist()})
Path(sys.argv[2]).parent.mkdir(parents=True, exist_ok=True)
Path(sys.argv[2]).write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
print("Synthetic Python reference written:", sys.argv[2])
