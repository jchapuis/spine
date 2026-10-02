---
language: en
license: apache-2.0
library_name: peft
base_model: jaredpalmer/kev-4b
base_model_relation: finetune
pipeline_tag: text-classification
tags:
  - decision-model
  - calibration
  - lora
  - code-review
  - scala
  - typesafe
  - qwen3.5
metrics:
  - accuracy
  - brier_score
---

# kev-4b-spine-scala

[Kev-4B](https://huggingface.co/jaredpalmer/kev-4b) fine-tuned to judge Scala 3 code changes against six team
conventions, one yes/no question each. It is meant to be served with [kev](https://github.com/jaredpalmer/kev)
(`python -m kev.serve --run jchapuis/kev-4b-spine-scala`). [abide](https://github.com/coldteadotai/abide) then
queries it on every edit a coding agent makes, through kev's TypeSafe-compatible `/v1/systemone` API.

It is a rank-16 LoRA adapter plus a pointer head on `Qwen/Qwen3.5-4B-Base`, warm-started from Kev-4B. It returns a
calibrated probability per question (fitted temperature 1.32) and generates no text.

## Questions it was trained on

The questions must be sent **verbatim**: the fine-tune learned their exact wording. They are in
[`.abide/rubric.json`](https://github.com/jchapuis/spine/blob/master/.abide/rubric.json):

- `fail-loud-no-silent-fallback`: a failure hidden behind `getOrElse` / `recover` / a side effect after a failed step
- `server-architecture-service-depends-on-service`: a Service holding another concrete Service instead of an algebra
- `server-architecture-logic-placement`: orchestration inlined in a Service, or an algebra shipped as an object
- `scala-plain-readable-names`: abbreviated, vague or metaphorical identifiers
- `cats-right-combinator`: a flatMap chain that should be `mapN`, a fold that should be `foldMap`
- `factor-out-within-diff-duplication`: the same constant, fixture or idiom defined twice in one change

The state is what abide sends: `{"file", "diff"}` for an edit and `{"files", "diff"}` for a whole turn.

## Results

| | Kev-4B | this model |
|---|---|---|
| held-out synthetic accuracy (851 questions) | 0.704 | **0.968** |
| decided within a 5% error budget | 5% | **100%** |
| public decision-v7 regression set | 0.862 | 0.858 |
| real clean diffs (892 rule checks): probability ≥ 0.5 | 111 | **2** |
| real clean diffs: probability ≥ 0.8 | 1 | **0** |

The real diffs were scored privately and were never used for training.

**Limitations.** `scala-plain-readable-names` misses most naming violations (89% accuracy, mostly false negatives).
Borderline duplication in real code, and a silent default on a raw `Map` of settings, are often missed.

## Training

One epoch with kev's `kev-finetune` recipe (lr 2e-5, batch 2 × accum 4, 2,000 public replay records), with state
limit 1,536 tokens, on 1,028 records. The data is 1,236 + 230 synthetic Scala diffs written by LLM generators from a
labelling spec. Blind re-labelling agreed with 96–100% of their labels per rule. The data is invented; it contains
no code from any real repository. Training took about 20 minutes on one H100 with Modal. Spec, data and scripts:
[`.abide/finetune/`](https://github.com/jchapuis/spine/tree/master/.abide/finetune).
