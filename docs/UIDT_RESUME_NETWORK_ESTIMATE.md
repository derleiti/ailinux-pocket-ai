# Android UIDT: Resume network-byte estimate (RC15 development)

Observed symptom: Android 17 / Pocket AI RC13 presented Qwen or Gemma
.part as almost/full-size, but the UIDT job remained queued. This
documentation is a scheduling hypothesis, not a proven cause on device.

Previously, JobInfo.Builder.setEstimatedNetworkBytes() always advertised the
total model length (around 2 to 3 GB), even after the persisted partial
already contained almost all bytes. This contradicts Android's API contract
to estimate network traffic the current job will perform.

The updated UIDT planner:
- Reads the existing .part length, not the journal's displayed progress.
- Subtracts it from the trusted total size, guards overflows/missing values.
- Budgets 16 KiB for HEAD, HTTP Range/416 and ETag/If-Range checks even at
  exact EOF. It does not claim that a local model is installed.
- Uses setMinimumNetworkChunkBytes() (API 33+) with at most 64 KiB for
  resumable downloads. Actual transfer verification still runs in ModelStore.
- Leaves the network estimate unknown when no trustworthy total is known.
- Preserves VPN-aware NetworkRequest, user-initiated scheduling, pause,
  explicit consent on requeue and all existing partial files and validators.

References:
- https://developer.android.com/reference/android/app/job/JobInfo.Builder
- https://developer.android.com/develop/background-work/background-tasks/uidt

Acceptance is not a green JVM test alone. On the paired physical device,
validate the model's exact size and SHA-256, record JobScheduler's actual
pending reasons, and observe a genuine resumed job that verifies/publishes
the model without redownloading all existing bytes. Do not delete partials,
clear app data or claim completion until this happens.

This change deliberately does not address other outstanding Android/TPU
compatibility, MCP tool planning or atomic model publication issues.
