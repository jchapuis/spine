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

[Kev-4B](https://huggingface.co/jaredpalmer/kev-4b) fine-tuned to judge Scala 3 code changes against a team's
conventions: six judgment rules no linter can express, plus ten groups covering 49 mechanical rules from the
team's compliance gate (ripgrep and Scalafix). Each rule or group is one yes/no question. It is meant to be served
with [kev](https://github.com/jaredpalmer/kev) (`python -m kev.serve --run jchapuis/kev-4b-spine-scala`).
[abide](https://github.com/coldteadotai/abide) then queries it on every edit a coding agent makes, through kev's
TypeSafe-compatible `/v1/systemone` API.

It is a rank-16 LoRA adapter plus a pointer head on `Qwen/Qwen3.5-4B-Base`, warm-started from Kev-4B. It returns a
calibrated probability per question (fitted temperature 1.52) and generates no text.

## Questions it was trained on

The questions must be sent **verbatim**: the fine-tune learned their exact wording. They are in
[`.abide/rubric.json`](https://github.com/jchapuis/spine/blob/master/.abide/rubric.json).

Judgment rules:

- `fail-loud-no-silent-fallback`: a failure hidden behind `getOrElse` / `recover` / a side effect after a failed step
- `server-architecture-service-depends-on-service`: a Service holding another concrete Service instead of an algebra
- `server-architecture-logic-placement`: orchestration inlined in a Service, or an algebra shipped as an object
- `scala-plain-readable-names`: abbreviated, vague or metaphorical identifiers
- `cats-right-combinator`: a flatMap chain that should be `mapN`, a fold that should be `foldMap`
- `factor-out-within-diff-duplication`: the same constant, fixture or idiom defined twice in one change

Mechanical groups (the rules each covers are listed in
[`mechanical-groups.json`](https://github.com/jchapuis/spine/blob/master/.abide/finetune/mechanical-groups.json)):
`mech-fail-loud-unsafe`, `mech-fail-loud-boundary`, `mech-iron-invariants`, `mech-scala-imperative`,
`mech-scala-modifiers`, `mech-scala-syntax`, `mech-scala-ascriptions`, `mech-effects`, `mech-duplication` (whole
turn) and `mech-frontend` (Laminar code).

The state is what abide sends: `{"file", "diff"}` for an edit and `{"files", "diff"}` for a whole turn.

## Results

| | Kev-4B | this model |
|---|---|---|
| held-out accuracy (5,366 questions, synthetic and real) | — | **0.985** |
| decided within a 5% error budget | — | **100%** |
| real diffs, judgment rules: violations blocked (p ≥ 0.8) | 0 / 8 | **3 / 8** |
| real diffs, judgment rules: clean checks noted (p ≥ 0.5) / blocked | 111 / 1 of 792 | **1 / 0** |
| real diffs, mechanical groups: violations blocked | 0 / 29 | **19 / 29**¹ |
| real diffs, mechanical groups: clean checks noted / blocked | 559 / 10 of 1,237 | **7 / 5**¹ |

Kev-4B scored 0.630 on the earlier synthetic-only held-out set (2,208 questions), where this recipe without real
code reached 0.972. The real diffs are 200 changes from our own repositories, gate-labelled for the mechanical
groups. They were scored privately and were never used for training. The public decision-v7 regression check was
0.858 for the previous version (Kev-4B: 0.862); it was not rerun for this one.

¹ Measured with this model at temperature 1.00. The rerun at the served temperature (1.52) stopped after 347 of the
366 real records, so it is not reported. Temperature scaling does not move the p ≥ 0.5 notes, and can only lower the
blocked counts.

**Limitations.** `mech-duplication` (the same number or import block repeated across files) is the weakest group
(0.81 held-out, 0 of 5 real violations caught). `scala-plain-readable-names` and within-diff duplication still miss
violations, mostly as false negatives. The compliance gate stays the final check at commit time.

## Training

One epoch with kev's `kev-finetune` recipe (lr 2e-5, batch 2 × accum 4, 2,000 public replay records), with state
limit 1,536 tokens, on 6,826 records:

- **Judgment rules:** 1,466 synthetic Scala diffs written by LLM generators from a labelling spec. Blind
  re-labelling agreed with 96–100% of their labels per rule.
- **Mechanical groups, synthetic:** 1,175 LLM-written examples, labelled by running the real compliance gate on each.
- **Mechanical groups, real:** 2,864 single-hunk edits mined from the history of
  [endless](https://github.com/endless4s/endless), [endless-transaction](https://github.com/endless4s/endless-transaction)
  and one private project of the author's, again labelled by the gate. The weights may therefore reflect fragments of
  that code; the mined data itself is not published.

Training took about 35 minutes on one H100 with Modal. Spec, synthetic data and scripts:
[`.abide/finetune/`](https://github.com/jchapuis/spine/tree/master/.abide/finetune).
