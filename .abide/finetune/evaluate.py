#!/usr/bin/env python3
"""Real-code check: what abide would do on our own diffs with stock Kev-4B vs the fine-tune, each as served.

    cd tools/kev && uv run --extra serve python ../../.abide/finetune/evaluate.py ../../.abide/finetune/runs/<run>/checkpoint

Runs on this Mac (real/real.jsonl never leaves it). The synthetic development comparison, temperature fit and bootstrap
come from the Modal run (runs/scala-v1/result.json); this script only covers the real diffs: per rule, how many clean
diffs would get a note (>=0.5) or a forced repair (>=0.8), and what each model says about the few real violations.
"""
import gc, json, shutil, sys
from pathlib import Path

import torch
from kev.benchmark import evaluate_records
from kev.checkpoint import Checkpoint, LoadOptions, read_meta
from kev.data import load_records
from kev.metrics import probabilities_at_temperature
from kev.model import SERVE_MAX_BRANCH, SERVE_MAX_PACKED, SERVE_MAX_STATE
from kev.predictors import LocalPredictor

DATA = Path(__file__).resolve().parent
BASELINE = "jaredpalmer/kev-4b"
# kev.serve's limits: real diffs run longer than anything trained on, and abide sends them whole.
CONTEXT = {"max_state": SERVE_MAX_STATE, "max_branch": SERVE_MAX_BRANCH, "max_packed": SERVE_MAX_PACKED}
ACT, FLAG = 0.8, 0.5


def real_report(run, out):
    """Per rule: clean diffs that land in the note / repair bands, and the violation probabilities, at the served temperature."""
    temperature = read_meta(Checkpoint(run).path).temperature
    predictor = LocalPredictor(run, "mps", LoadOptions(temperature=1.0, merge=True), context=CONTEXT)
    try:
        rows = evaluate_records(load_records(DATA / "real/real.jsonl"), predictor, out)[1]
    finally:
        del predictor; gc.collect(); torch.mps.empty_cache()
    by_rule = {}
    for r in rows:
        s = by_rule.setdefault(r["question"], {"clean": 0, "flag": 0, "act": 0, "violations": []})
        p = float(probabilities_at_temperature(r, temperature)[r["keys"].index("true")])
        if r["keys"][r["label"]] == "true": s["violations"].append(round(p, 2))
        else: s["clean"] += 1; s["flag"] += p >= FLAG; s["act"] += p >= ACT
    return {"temperature": temperature, "rules": by_rule}


def main(checkpoint):
    out = Path(checkpoint).parent / "eval"
    shutil.rmtree(out, ignore_errors=True)   # kev.benchmark refuses to write into an existing directory
    real = {"baseline": real_report(BASELINE, out / "baseline"), "finetuned": real_report(checkpoint, out / "finetuned")}
    (out / "real.json").write_text(json.dumps(real, indent=2))
    b, f = real["baseline"], real["finetuned"]
    print(f"real diffs, as served (baseline T {b['temperature']:.2f}, finetuned T {f['temperature']:.2f}): clean diffs that get a note / a forced repair")
    for q in f["rules"]:
        rb, rf = b["rules"][q], f["rules"][q]
        print(f"  {q:<48} baseline {rb['flag']}/{rb['clean']} note, {rb['act']} repair  ->  finetuned {rf['flag']}/{rf['clean']} note, {rf['act']} repair"
              + (f"   violations {rb['violations']} -> {rf['violations']}" if rf["violations"] else ""))


if __name__ == "__main__":
    main(sys.argv[1])
