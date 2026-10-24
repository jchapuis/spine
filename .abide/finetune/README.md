# Fine-tuning kev on the abide rubric

`kev-serve` serves `jchapuis/kev-4b-spine-scala`: Kev-4B fine-tuned to answer the six questions in
`../rubric.json` on the diffs abide sends. This folder rebuilds it.

The model learns the rubric's exact question text. **Editing a question in `rubric.json` means retraining.**

## Pipeline

| Step | Command | Output |
|---|---|---|
| 1. Write labelled examples | subagents following `SPEC.md` | `synthetic/raw/<generator>/part-N.txt` (tracked) |
| 2. Check labels blind | a fresh labeller on a sample, compared with the key | `synthetic/qa*/` (tracked) |
| 3. Build records | `python3 assemble.py 'synthetic/raw/*/part-*.txt' synthetic/all.jsonl` | rubric questions copied verbatim + labels |
| 4. Split | `python3 split_data.py synthetic/all.jsonl --out synthetic/split` | train / calibration / development |
| 5. Train | `KEV_HF_SECRET=<modal secret with HF_TOKEN> uvx modal run kev_modal.py::train --data synthetic/split --name <run> --init-from jaredpalmer/kev-4b` | H100, ~20 min, ~$1.30 |
| 6. Pull + clean up | `uvx modal run kev_modal.py::pull --name <run> --checkpoint`, then `::teardown --everything --cache --yes` | `runs/<run>/` |
| 7. Real-code check | `python3 extract_real.py real/candidates.txt`, label it twice blind, `merge_labels.py`, `assemble.py`, then `evaluate.py runs/<run>/checkpoint` from the kev venv | false alarms on our own clean diffs |

The shipped run (`scala-v2c`) split the v1 examples (`gen-*`) and the v2 examples (`v2-*`) separately, then
concatenated the partitions, so v1's held-out records stayed unseen.

`real/` and `runs/` are gitignored. `real/` holds the 200 held-out evaluation diffs from all projects' history; they are
only ever scored locally and are never used for training or sent to Modal.

From v4 the mechanical groups also train on real history, mined by `mine_real_mech.py` into `synthetic/real-mech/`
(gitignored: this repo is public, the source is not). Only repos cleared for a public model are mined: gritmaps,
endless, endless-transaction. Never the TeraSol-SA repos. The evaluation commits are skipped.

## Results (scala-v2c)

| | stock Kev-4B | fine-tuned |
|---|---|---|
| synthetic held-out accuracy (851 questions) | 0.704 | **0.968** |
| decided at a 5% error budget | 5% | **100%** |
| public set (forgetting check) | 0.862 | 0.858 |
| clean real diffs (892 rule checks): notes ≥0.5 | 111 | **2** |
| clean real diffs: forced repairs ≥0.8 | 1 | **0** |

**Known weakness: `scala-plain-readable-names`.** It catches about 89% on held-out data and misses most naming
violations, even after 53 targeted examples. It rarely fires, so it rarely false-alarms, but the gate's jargon pass
remains the real check for names.

Also missed: a silent default on a lookup into a raw `Map` of settings, e.g. `c.get("port").getOrElse(8080)`.

## Files from kev

`kev_modal.py` and `split_data.py` are copied from [kev](https://github.com/jaredpalmer/kev)'s `kev-finetune` skill
(Apache-2.0, Jared Palmer). The changes in `kev_modal.py` are marked `spine:`:
- it pins the kev commit we serve
- it allows 1,536-token diffs (kev trains on 384 by default)
