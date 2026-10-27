# RikkaHub Tablet Release

- Package: `me.rerere.rikkahub.tablet` (independent of upstream and `.debug`).
- Label: RikkaHub Tablet; ARM64/TB373FU target, Firebase disabled.
- Release is non-debuggable with the project's R8 optimization enabled.
- New signing identity, private PKCS12/password kept offline in the builder's private backup directory.
  Neither key nor signing configuration is part of this repository or release.
- Includes auto context compression (percentage), leader-only team tools, runtime team concurrency gate,
  bounded rendering, incremental persistence and tablet memory policies.
- Existing installed apps/data are not overwritten. Use native backup/import if migrating data;
  do not copy a live database between packages.
- Earlier debug regression tests passed, but release itself needs startup/real-model validation;
  do not call it fully stable merely because R8 completed.
- Full local stress evidence/credentials/database snapshots are excluded from source publication.

See `AGENT-TEAM-FIX-20261028.md`, `MEMORY-OPT-20261028.md` and `TB373FU-MEMORY-20261028.md`.
