import re

from fastapi import FastAPI
from pydantic import BaseModel


app = FastAPI(title="CivicShield AI Service", version="0.1.0")


POLICE_KEYWORDS = {
    "theft", "stolen", "robbery", "assault", "attack", "fight", "violence",
    "weapon", "gun", "knife", "murder", "kidnap", "harassment", "fraud",
    "accident", "crash", "drunk", "rape", "crime", "criminal", "police",
    "drug", "abuse", "threat",
}

CORPORATION_KEYWORDS = {
    "garbage", "trash", "waste", "sewage", "drain", "pothole", "road",
    "streetlight", "street light", "water", "leak", "pipe", "tree",
    "mosquito", "stray", "electricity", "pole", "sidewalk", "footpath",
    "sanitation", "traffic signal", "hoarding", "encroachment",
}


class ClassifyRequest(BaseModel):
    text: str


class ClassifyResponse(BaseModel):
    category: str
    route: str
    score: dict[str, int]


def _tokenize(text: str) -> set[str]:
    return set(re.findall(r"[a-zA-Z]+", text.lower()))


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "service": "ai-service"}


@app.post("/classify", response_model=ClassifyResponse)
def classify(payload: ClassifyRequest) -> ClassifyResponse:
    tokens = _tokenize(payload.text)

    police_hits = sum(1 for k in POLICE_KEYWORDS if k in payload.text.lower() or k in tokens)
    corp_hits = sum(1 for k in CORPORATION_KEYWORDS if k in payload.text.lower() or k in tokens)

    if police_hits > corp_hits and police_hits > 0:
        route = "police"
        category = "safety_incident"
    elif corp_hits > 0:
        route = "corporation"
        category = "civic_issue"
    else:
        route = "corporation"
        category = "unclassified"

    return ClassifyResponse(
        category=category,
        route=route,
        score={"police": police_hits, "corporation": corp_hits},
    )
