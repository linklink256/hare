# Command DeepSeek debug real-model stress test — 2026-10-28

## Scope and verdict
Actual installed debug build 451a401d; configured provider model commandcode/deepseek-v4.1-flash.
Model context metadata left unchanged at 1,000,000. Threshold temporarily 10% (100,000), keep 1;
team concurrency temporarily 4. All model traffic entered debug's loopback Web API/ChatService,
not a separate direct provider test. Separate stress assistant and conversations; old conversations untouched.

**Overall NOT PASS.** Compression functional checks passed, but two team implementation defects
were reproduced and memory guard interrupted two stages. No source fix or stress-build reinstall in this run.

## Actual provider-reported totals
78 recorded requests: 68 CHAT step requests and 10 COMPRESSION; input 2,491,361, output 30,507,
cached 1,224,320 (cached is included within input; not an additional billable-token claim).
Streaming cancellations may omit/incompletely report provider usage; totals describe persisted records,
not a guaranteed upper bound on billed requests/tokens. No currency cost estimated.

## Functional evidence
- Lead input 203,815 tokens -> next normal reply input 1,878 (~99.1% reduction).
- Four actual teammates concurrently received ~203,541–204,124 prompt tokens apiece.
- Each generated checkpoints and recovered TEAM-COPPER-91 plus its distinct worker_secret.
- Main leader/children together persisted 10 checkpoints. Original large seed and first reply IDs/parts
  verified identical in stopped-process SQLite snapshot.
- Leader real calls: spawn_teammate, send_message, wait_agent, list_agents,
  team_task_create/list/update. Four main audit tasks persisted DONE revision 2.
- Restart resumed leader's next large-input compaction; original sentinels and team code correct.
- Boundary team: full-capacity spawning rejected, interrupt was_running=true, subsequent epsilon
  spawn succeeded; revision=0 rejected stale_revision and actual revision update succeeded;
  reserved name lead and duplicate beta rejected. Boundary task remains PENDING intentionally;
  it must not be counted among the four main DONE tasks.
- Model's final narrative mislabeled CAPACITY_REJECTED=false even while quoting the rejection;
  verdicts were derived from actual tool JSON/SQLite, not model self-report.

## Reproduced defects
1. **Existing teammates bypass concurrency limit.** Boundary team configured 4 simultaneous slots.
   Sending to five already-existing members before waiting resulted in list_agents returning
   five running=true; monitor saw six generating including the leader. Spawn path enforces
   occupied slots, but AgentTeamService.sendToMember/sendToConversation does not.
2. **Teammates exposed leader-only tools.** epsilon's real list_agents returned {agents:[]}.
   This created a nested empty team. ChatToolFactory gates only on assistant.enableAgentTeam;
   spawned children use the same assistant, and there is no member/leader restriction at that gate.
   A prompt saying not to spawn is not an authorization boundary.

## Memory and safety
Initial multi-large-history run hit guard at 1,542,832 KiB MemAvailable and subsequently observed
~1.27GiB; extra boundary attempt on loaded history hit 1,461,336KiB. Stopped test generations
and debug only; never rebooted/killed chat/system services. Resuming after restarting debug
succeeded. Fresh short boundary conversations needed less memory. This indicates aggregate
loaded conversation/render/serialization memory is not solved by request-context compaction;
no heap dump or attribution proof was collected. No long-term stability pass claimed.

## Restore and evidence
Original settings verified restored (40%, keep1, team limit8, providers byte-identical, web disabled),
except one retained stress assistant and automatic launch count. Test conversations/teams retained.
Debug stopped and foreground returned to formal chat; final MemAvailable ~4GiB.
Sensitive full settings/DB/API snapshots stay local under results/command-deepseek-stress-20261028,
mode700 directory/mode600 files; do not print/upload them. receipt.json is the result summary.
