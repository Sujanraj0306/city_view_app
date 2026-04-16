import base64
import io
import logging
from pathlib import Path

import torch
from fastapi import FastAPI, HTTPException
from huggingface_hub import snapshot_download
from PIL import Image
from pydantic import BaseModel


MODEL_DIR = Path("/app/models")
HELMET_REPO = "aneesarom/Helmet-Violation-Detection"
POTHOLE_REPO = "taroii/pothole-detection-model"
DEVICE = torch.device("cpu")

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("civicshield.ai")

app = FastAPI(title="CivicShield AI Service", version="0.2.0")

_helmet_model = None
_pothole_model = None
_pothole_processor = None


class PredictRequest(BaseModel):
    image_base64: str


class PredictResponse(BaseModel):
    detected: bool
    confidence: float
    label: str


def _decode_image(image_base64: str) -> Image.Image:
    try:
        if image_base64.lstrip().startswith("data:") and "," in image_base64:
            image_base64 = image_base64.split(",", 1)[1]
        raw = base64.b64decode(image_base64)
        return Image.open(io.BytesIO(raw)).convert("RGB")
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Invalid image_base64: {e}")


@app.on_event("startup")
def load_models() -> None:
    """Download and load both models. Weights are cached in /app/models volume."""
    global _helmet_model, _pothole_model, _pothole_processor
    MODEL_DIR.mkdir(parents=True, exist_ok=True)

    log.info("Downloading helmet model: %s", HELMET_REPO)
    helmet_dir = snapshot_download(repo_id=HELMET_REPO, cache_dir=str(MODEL_DIR))
    helmet_weights = Path(helmet_dir) / "best.pt"
    from ultralytics import YOLO
    _helmet_model = YOLO(str(helmet_weights))
    _helmet_model.to(DEVICE)
    log.info("Helmet model ready. classes=%s", _helmet_model.names)

    log.info("Downloading pothole model: %s", POTHOLE_REPO)
    pothole_dir = snapshot_download(repo_id=POTHOLE_REPO, cache_dir=str(MODEL_DIR))
    from transformers import AutoImageProcessor, AutoModelForImageClassification
    _pothole_processor = AutoImageProcessor.from_pretrained(pothole_dir)
    _pothole_model = AutoModelForImageClassification.from_pretrained(pothole_dir)
    _pothole_model.to(DEVICE).eval()
    log.info("Pothole model ready. labels=%s", _pothole_model.config.id2label)


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "service": "ai-service",
        "helmet_loaded": _helmet_model is not None,
        "pothole_loaded": _pothole_model is not None,
    }


def _normalize(label: str) -> str:
    """Clean up model labels: 'with helmet' -> 'helmet', 'no%20pothole' -> 'no_pothole'."""
    label = label.replace("%20", " ").strip().lower()
    mapping = {
        "with helmet": "helmet",
        "without helmet": "no_helmet",
        "no pothole": "no_pothole",
    }
    return mapping.get(label, label.replace(" ", "_"))


@app.post("/predict/helmet", response_model=PredictResponse)
def predict_helmet(req: PredictRequest) -> PredictResponse:
    if _helmet_model is None:
        raise HTTPException(status_code=503, detail="Helmet model not loaded")

    image = _decode_image(req.image_base64)
    results = _helmet_model.predict(source=image, device="cpu", verbose=False)

    # Only consider the helmet classes — ignore rider/number_plate boxes
    helmet_classes = {"with helmet", "without helmet"}
    best_conf = 0.0
    best_raw = "none"

    for r in results:
        if r.boxes is None or len(r.boxes) == 0:
            continue
        confs = r.boxes.conf.cpu().numpy()
        classes = r.boxes.cls.cpu().numpy().astype(int)
        for c, cls_idx in zip(confs, classes):
            cls_name = r.names[int(cls_idx)]
            if cls_name in helmet_classes and float(c) > best_conf:
                best_conf = float(c)
                best_raw = cls_name

    label = _normalize(best_raw)
    # "detected" means a violation — a rider without a helmet
    detected = label == "no_helmet"

    return PredictResponse(
        detected=detected,
        confidence=round(best_conf, 4),
        label=label,
    )


@app.post("/predict/pothole", response_model=PredictResponse)
def predict_pothole(req: PredictRequest) -> PredictResponse:
    if _pothole_model is None or _pothole_processor is None:
        raise HTTPException(status_code=503, detail="Pothole model not loaded")

    image = _decode_image(req.image_base64)
    inputs = _pothole_processor(images=image, return_tensors="pt").to(DEVICE)
    with torch.inference_mode():
        outputs = _pothole_model(**inputs)
    probs = outputs.logits.softmax(dim=-1)[0]
    conf, idx = torch.max(probs, dim=0)
    raw_label = _pothole_model.config.id2label[int(idx.item())]
    label = _normalize(raw_label)
    detected = label == "pothole"

    return PredictResponse(
        detected=detected,
        confidence=round(float(conf.item()), 4),
        label=label,
    )
