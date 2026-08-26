# Memory Prompt Cache Capture Report

Date: 2026-08-26

## Scope

This report records emulator packet captures for the ordinary memory mode after the memory snapshot prepare/commit fix. It compares two Anthropic-native relay providers using explicit `cache_control` and a one-hour TTL.

Secrets and authorization headers are excluded. Provider URLs and API keys are intentionally not recorded.

## Test Environment

- App: Heimao AI `2.4.9` (`versionCode 176`), release build
- Base source commit: `a6a18c6061c79c12e73fe32c96d81c0d51295c7b`
- Pending source change: ordinary-memory snapshot prepare/commit fix
- Device: Android 15 emulator, `x86_64`
- Protocol: Anthropic native `POST /v1/messages`, streaming SSE
- Prompt cache: enabled, explicit `cache_control`, TTL `1h`
- Memory mode: ordinary mode, assistant-scoped, not layered
- Tools: `memory_tool` and `get_time_info`

The capture proxy redacted `x-api-key`, `authorization`, and equivalent credential headers before writing records.

## Test Matrix

Each provider used a separate assistant and separate memory scope.

1. Warm a short stable prompt.
2. Repeat a normal request.
3. Create one memory through `memory_tool`.
4. Send two normal requests after the new snapshot is committed.
5. Edit that memory through `memory_tool`.
6. Send two normal requests after the edited snapshot is committed.
7. Append two stable Chinese prompt blocks of exactly 2,000 characters each.
8. Warm and repeat the long prompt.
9. Create a second memory and send two normal requests.
10. Edit the second memory and send two normal requests.

A create or edit operation uses two model requests: the tool-call request and the continuation after the tool result.

## Provider A: HuniuHub Claude Opus 4.6

Model: `claude-opus-4-6`

### Stable-prefix evidence

For requests 18 through 22, the canonical SHA256 of `tools + system + memory snapshot` was identical:

```text
ae714018ed1c296137638e9a6fc1884d9b5648f5e65a07377026c3b14c4f8fe6
```

The request structure was also identical before history:

```text
system[0]: stable prompt, 4,164 characters
system[1]: complete memory snapshot, 259 characters, cache_control ttl=1h
tools: 2 stable definitions
```

Despite that identical stable prefix, cache results alternated:

| Stage | Input | Cache read | Cache create | Result |
|---|---:|---:|---:|---|
| Long prompt warm | 19 | 0 | 6,171 | Created stable prefix |
| Long prompt repeat | 19 | 6,171 | 33 | Stable prefix hit |
| Create memory, tool request | 32 | 50 | 4,845 | Relay rebuilt a large prefix |
| Create memory, continuation | 49 | 4,925 | 90 | Previous prefix largely hit |
| New snapshot, first request | 18 | 0 | 6,453 | Entire prefix recreated |
| New snapshot, second request | 20 | 0 | 6,482 | Relay recreated again |
| Next edit request | 41 | 6,453 | 66 | Earlier cache became readable |
| Edited snapshot, first request | 16 | 19 | 5,176 | Large prefix recreated |
| Edited snapshot, second request | 22 | 0 | 6,740 | Large prefix recreated |
| Delayed stable request 1 | 17 | 45 | 5,148 | Large prefix recreated |
| Delayed stable request 2 | 19 | 6,740 | 72 | Full prefix hit |
| Delayed stable request 3 | 18 | 61 | 5,186 | Large prefix recreated |

A captured request body was then repeated four times without changing any byte of its logical request content. All four direct repeats returned:

```text
cache_read_input_tokens = 5247
cache_creation_input_tokens = 0
```

This proves that Provider A supports the cache, but cache visibility varies while the App grows history and moves the history breakpoint. Relay routing or cache propagation adds variability beyond the App-side invalidation.

## Provider B: `[特价纯血]claude-opus-4-7`

The full model ID and display name were preserved, including the Chinese field and brackets.

Provider B produced consistent cache accounting and clearly exposed the App-side behavior.

### Automation note

The pre-fix Provider B foreground guard relaunched `RouteActivity` before each top-level user message. In this App, that action created a fresh conversation, so the create/edit tool-call pairs were same-conversation operations, while the following warm/hit probes used new conversation cache keys. These results remain valid for cross-conversation snapshot rebuilding and provider cache accounting, but they do not by themselves prove same-conversation Patch reuse. The post-fix validation below corrects this by starting one conversation once and sending every probe without relaunching the Activity.

### Short-prompt stage

The initial prompt was below this route's effective cache threshold. Cache fields stayed at zero until history grew beyond roughly 2,000 tokens.

| Stage | Input | Cache read | Cache create |
|---|---:|---:|---:|
| Baseline warm | 1,417 | 0 | 0 |
| Baseline repeat | 1,456 | 0 | 0 |
| Create memory, tool request | 1,529 | 0 | 0 |
| Create memory, continuation | 1,823 | 0 | 0 |
| New snapshot, first request | 1,829 | 0 | 0 |
| New snapshot, second request | 1,870 | 0 | 0 |
| Edit memory, tool request | 1,948 | 0 | 0 |
| Edit memory, continuation | 2,242 | 0 | 0 |
| Edited snapshot, first request | 36 | 0 | 2,131 |
| Edited snapshot, repeat | 19 | 2,131 | 61 |

### Two 2,000-character prompt blocks

| Stage | Input | Cache read | Cache create | Result |
|---|---:|---:|---:|---|
| Long prompt warm | 24 | 0 | 6,433 | Created stable prompt and memory prefix |
| Long prompt repeat | 27 | 6,433 | 0 | Full hit |
| Create memory, tool request | 64 | 6,433 | 0 | Old snapshot fully hit |
| Create memory, continuation | 365 | 6,433 | 0 | Old snapshot fully hit; delta remained dynamic |
| New snapshot, first request | 29 | 0 | 6,485 | Entire prefix recreated |
| New snapshot, repeat | 31 | 6,485 | 0 | Full hit |
| Edit memory, tool request | 71 | 6,485 | 0 | Old snapshot fully hit |
| Edit memory, continuation | 381 | 6,485 | 0 | Old snapshot fully hit; delta remained dynamic |
| Edited snapshot, first request | 31 | 0 | 6,485 | Entire prefix recreated |
| Edited snapshot, repeat | 33 | 6,485 | 0 | Full hit |

This route demonstrates deterministic behavior: the snapshot fix correctly sends a change once as a dynamic delta, but promoting the changed memory list to the next stable snapshot invalidates the complete cached prefix.

## Confirmed Request Layout

The App currently builds ordinary memory as one serialized prompt:

```text
system[0] = assistant prompt + stable tool prompt text
system[1] = one JSON document containing every memory record
               cache_control is attached here
messages[...] = history
               a moving history cache_control is attached near history end
latest user / tool loop = dynamic suffix
```

All memory records therefore occupy one Claude system text block. Claude cache matching is block-oriented. Creating, editing, or deleting one record changes `system[1]` as a whole.

Because the memory breakpoint is after tools, the assistant prompt, and the complete memory JSON block, invalidating the memory block also causes the provider to recreate the prefix that includes stable tools and the assistant prompt.

## Root Cause

The snapshot prepare/commit fix is semantically correct:

```text
current request = old stable snapshot + one dynamic delta
next request = new stable snapshot + no repeated delta
```

However, the stable snapshot representation is too coarse:

```text
all memories -> one JSON system block -> one cache boundary
```

Consequences:

1. Creating one memory changes the only memory block.
2. Editing one memory changes the only memory block.
3. Deleting one memory changes the only memory block.
4. The memory cache boundary can no longer reuse the previous memory block.
5. The provider recreates the full prefix, including tools and the assistant prompt.

Before the snapshot fix, the old snapshot never advanced. That accidentally preserved a high cache-hit rate because the stale memory block remained unchanged forever and every update stayed in a repeated dynamic delta. The high hit rate was therefore coupled to incorrect memory state.

The current fix corrected state progression and exposed the existing coarse cache layout.

## Required Fix Properties

A correct optimization must preserve snapshot correctness while reducing block invalidation:

- Room remains the source of truth.
- A memory change is sent once as a delta and is eventually represented in the stable snapshot.
- Stable assistant instructions and tool definitions keep their own reusable cache boundary.
- Memory records must not all be serialized into one mutable cache block.
- Creating one memory should preserve the cached prefix for all prior stable records.
- Editing one memory should invalidate only the smallest practical suffix or record block.
- Deleting one memory must not leave stale content authoritative.
- OpenAI-compatible and Google request semantics must remain unchanged.
- Claude tests must assert exact block order and `cache_control` placement, not only flattened message order.
- Generation tests must retain create/edit/delete once-only delta, cancellation, and stale-commit coverage.

## Post-fix Same-conversation Validation

After implementing append-only Base/Patch blocks and an independent `SYSTEM_END`, a new signed release APK was installed over the existing emulator data. One conversation was created once; all subsequent probes stayed in that conversation without relaunching the Activity.

The existing long prompt and Memory Base were warmed, memory ID 4 was edited through `memory_tool`, and two normal requests followed.

| Stage | Input | Cache read | Cache create | Stable system layout |
|---|---:|---:|---:|---|
| Same-chat warm | 23 | 6,516 | 0 | prompt + Base |
| Same-chat repeat | 23 | 6,516 | 37 | prompt + Base + moving history |
| Edit tool request | 65 | 6,553 | 36 | old prompt + Base fully reused |
| Edit continuation with dynamic Patch | 408 | 6,589 | 0 | old prompt + Base fully reused |
| First request after Patch commit | 6 | 6,516 | 513 | old prompt + Base read; new Patch/history created |
| Stable Patch repeat | 6 | 7,029 | 48 | prompt + Base + Patch read |

The captured wire layout after commit was:

```text
system[0] = 4,174-char stable assistant/tool prompt + SYSTEM_END
system[1] = 377-char Memory Base, unchanged, no boundary
system[2] = 353-char Memory Patch + MEMORY_END
history     = moving HISTORY_END
```

Before the fix, promoting an edited snapshot caused approximately 6,485 tokens to be recreated. In the corrected same-conversation capture, 6,516 old-prefix tokens remained readable and only 513 tokens were created on the first request containing the stable Patch. The following request read 7,029 cached tokens and created only 48 tokens of recent history.

This validates both goals:

1. The snapshot still advances after one successful dynamic delta.
2. A memory edit no longer rebuilds the stable prompt, tools, Base, and all prior memory content.

## Raw Evidence

Local, credential-redacted capture artifacts were produced under the system temporary directory:

```text
heimao-huniu-capture.jsonl
heimao-huniu-cache-analysis.csv
second-provider-capture.jsonl
second-provider-cache-analysis.csv
```

They are intentionally not committed because they contain full conversation request bodies and test history. API keys are not present in the capture records.
