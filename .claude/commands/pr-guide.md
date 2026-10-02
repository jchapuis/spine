---
description: Generate or refresh the Plannotator guided-review link for a PR
---

Give **one PR** a guided-review link, and put that link in its description.
Re-running refreshes it.

Argument: `$ARGUMENTS` — a PR number or URL. Empty means the PR for the current branch
(`gh pr view --json number,title,headRefName,baseRefName,headRefOid`); otherwise resolve the named
one (`gh pr view <arg> --json …`) and read its head, base and repo from there rather than from the
checkout. One PR, never a batch: appending a link edits someone's description, and a guide you did
not read the diff for is worse than none.

## What a guide is

A reading order for a diff: sections, each with an overview, each listing files with a one-line
summary. Writing one is judgment work — grouping the diff into a narrative — so the value is in the
sections, not the plumbing.

A guide is a JSON document — the shape `--guide` takes:

```json
{ "title": "...", "intent": "...",
  "sections": [ { "title": "...", "overview": "...",
                  "diffs": [ { "file": "...", "summary": "..." } ] } ] }
```

**Sharing records nothing.** `plannotator guide share --guide … --patch …` is one-shot: it prints
`{id, url, deleteToken}` and writes no state. `~/.plannotator/guides/` holds records from
Plannotator's own review sessions instead — they carry a `headSha` but never a `share` block, so
they cannot tell you whether a link exists or what its `deleteToken` was. Do not look there.

So this command keeps its own record, one file per PR, at
`~/.claude-work/pr-guides/<host>__<owner>__<repo>/pr-<number>.json`:

```json
{ "headSha": "...", "sharedAt": "<ISO 8601 UTC>",
  "share": { "id": "...", "url": "...", "deleteToken": "..." } }
```

Without it the `deleteToken` is lost the moment the session ends, and every re-run strands the
previous link on the host forever.

## Steps

1. **Read this command's record** for the PR (path above) and compare its `headSha` to the PR's
   `headRefOid`.
   - Match → nothing to do; report the recorded `url`.
   - **Differs → regenerate.** A guide that omits files added since it was written is worse than
     none: the reviewer trusts it as a map and never sees them. `plannotator guide unshare <id>
     --token <deleteToken>` from the record first, then author fresh.
   - None → author one. A body that already carries a `Guided review` link with no record behind it
     (shared from another machine, or before this record existed) cannot be withdrawn — its token is
     gone. Replace the line and say the old link is left dangling.
2. **Author** (only when needed). Take the patch from the PR, not the checkout —
   `gh pr diff <number> > guide.patch` works for any PR, including one whose branch you do not have.
   Then write `guide.json`. Every file in the guide **must** appear in the patch or the share is
   rejected; the validation is one-way, so check the reverse yourself — compare the guide's file list
   against the patch's `diff --git` lines, because a file in the patch and in no section is silently
   dropped from the map. Genuine leftovers go in `unplacedFiles`.
3. **Share**, encrypted: `plannotator guide share --guide guide.json --patch guide.patch --json`.
   **Never `--public`** — these are private repos. Encryption keeps the host from reading the code;
   the key rides in the URL fragment, so anyone with the link can. No `--ttl`: a link in a PR body
   should outlive the review.
4. **Write the record** — the `{id, url, deleteToken}` it just printed, with the `headSha` the guide
   describes, to the path above. Do this before editing the PR, while the token is still in hand.
5. **Append to the description**, idempotently — one line above the trailing `🤖 Generated with …`,
   replacing any line already matching `Guided review`:

   ```
   [Guided review](<url>) — a reading order for this diff.
   ```

   `gh api repos/<owner>/<repo>/pulls/<number> -X PATCH -F body=@body.md`. **`-F`, not `-f`** — `-f` sends
   the literal string `@body.md`, the API accepts it, and the PR ends up with a one-word description.
   Read the body back and confirm. **Never `gh pr edit`** (deprecated API, fails).

   Append **one line and nothing else**. Do not reword the summary, add a paragraph, or drop the
   trailing attribution — the description is the author's, and this command only adds a link to it.

## Writing a good guide

- Order sections the way a reviewer should read them, not the way git lists files. Start where the
  change is decided; put mechanical churn and binaries last.
- `intent` says why the PR exists, in a sentence or two.
- A section `overview` explains what its files do *together* — the thing no per-file diff shows.
- A `summary` says what changed in that file and why, not what the file is.
- Match the repo's register: plain, short, no process narrative.

## Notes

- Works from a linked worktree — `gh pr view` resolves the branch you are on. With an argument it
  needs no checkout of that branch at all, only a directory inside the repo.
- `plannotator guide share` reads the repo, branch and head from git in the current directory for
  provenance. When the guide is for a PR you have not checked out, say so in the guide's `source`
  block rather than letting it stamp whatever branch you happen to be standing on.
- Sharing is refused when `PLANNOTATOR_SHARE=disabled` or `share: "disabled"` in
  `~/.plannotator/config.json`.
- To withdraw: `plannotator guide unshare <id> --token <deleteToken>`, from this command's record.
