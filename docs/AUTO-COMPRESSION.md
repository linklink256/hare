# Automatic context compression

Settings → 自动上下文压缩. Default: enabled, threshold 75% of the current model context window, keep 6 recent messages.
Each ChatService agent model request checks the threshold. Teammates use the same path.
The configurable range is 10–95%. The effective threshold is model.contextLength × percent / 100,
with output reserve allowed to lower it for safety; there is no fixed token cap or fixed 75% cap.
The percent uses a new DataStore key so old absolute-token values cannot be misinterpreted; existing
enabled/tail settings are preserved and the new threshold defaults to 75%. Estimates are heuristic, not exact tokenization.

Compression uses the conversation's own model and existing summary prompt, records COMPRESSION usage,
and inserts a context checkpoint without deleting history. It incurs an additional model request.
The active last message and whole-message tool call/result pairs remain intact. In-run overlay applies
checkpoints to the agent's immutable history on later steps. Existing provider usage predating a checkpoint
is ignored. Errors stop the turn without silently dropping messages; cancellation propagates.

Limitations: a huge active message, massive attachments, tool schemas or incorrect model window cannot
be guaranteed to fit. Provider-side summaries remain lossy. At least two new historical messages are
required. This does not reduce the full conversation's persisted size/UI history memory.

Verification (2026-10-28): Kotlin compile and GithubDebug APK assemble passed; 55 focused JVM tests
passed, including 15 new compression policy/overlay tests. Updated existing device debug in place with
matching signature; startup/settings/persistence checked. No live paid-provider compression or stress
scenario was run. Existing memory-safety working-tree changes are retained, not reverted.

Percent revision: 61 focused JVM tests passed (20 policy/overlay, 8 settings); compile, assemble and
in-place debug update passed. Device settings show 75%, enabled, keep 6. Live model compression
remains untested.
