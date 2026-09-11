# Saved Cards Persistence Design

**Date:** 2026-09-11  
**Status:** Approved approach A (private JSON file)  
**Scope:** Persist only the read/import card list across process death.

## Goal

Keep the in-app「读取列表」(`savedCards`) after the app is killed or restarted, so previously read or imported MIFARE dumps remain available for write/clone without re-scanning.

## Non-goals

- Do **not** restore `lastDump`, `sourceForWrite`, `mode`, `writeTrailers`, or `writeResult`.
- Do **not** sync to cloud, SharedPreferences, DataStore, or Room.
- Do **not** change NFC read/write algorithms or dump JSON schema fields beyond wrapping a list.

## Storage format

- **Path:** `context.filesDir/saved_cards.json` (app-private internal storage).
- **Content:** a top-level `JSONArray` of objects produced by existing `CardDump.toJson()`.
- **Load:** parse each element with `CardDump.fromJson(JSONObject)`.
- **Corrupt / missing file:** treat as empty list; log once; do not crash UI.

Example shape:

```json
[
  { "uid": "...", "typeName": "...", "sectors": [ ... ], "timestamp": 123 }
]
```

## Component design

### `CardStore` (new)

Package: `com.example.nfccopy.data.CardStore`

Responsibilities:

- `load(): List<CardDump>` — read file on a background thread (or return empty).
- `save(cards: List<CardDump>)` — write full list atomically (write temp + rename, or write then flush).

No dependency on Compose or ViewModel types beyond `CardDump`.

### `MainViewModel` changes

- Obtain `CardStore` via `AndroidViewModel(application)` **or** a small factory that passes `Application` / `Context`. Prefer `AndroidViewModel` to avoid new DI frameworks.
- On init: load persisted list into `savedCards` (post to main thread if load is async).
- After every list mutation, persist the current `savedCards` snapshot:
  - `saveCard`
  - `removeCard`
  - `clearSavedCards`
- Persistence writes run off the main thread (reuse existing executor or a dedicated single-thread executor) so UI stays responsive for large dumps.

Existing de-dupe rule unchanged: same `uidHex` + `blockCount` replaces the prior entry.

## UI / UX

- No new screens or settings.
- Existing「删除」and「全部清空」continue to work and also clear disk.
- Optional status toast/message on load failure is nice-to-have; silent empty list is acceptable for v1.

## Failure & concurrency

- Only one writer at a time (single-thread executor for store I/O).
- Failed save: keep in-memory list; log error; next successful mutation retries full rewrite.
- Failed load: empty list in memory; leave corrupt file in place until next successful save overwrites it (or optionally delete on parse failure — prefer overwrite on next save).

## Testing (manual)

1. Read or import a card → force-stop app → reopen → card still in「读取列表」.
2. Delete one card → restart → still deleted.
3. Clear all → restart → list empty.
4. Write mode can still select a persisted card as source.

## Alternatives considered

| Approach | Decision |
|----------|----------|
| A. Private JSON file | **Chosen** — zero new deps, matches existing `toJson`/`fromJson` |
| B. DataStore Preferences | Rejected — large dump blobs unfit for prefs |
| C. Room | Rejected — overkill for a small ordered list |

## Open questions

None for v1. (Future: optional export-all / import-all of the store file.)
