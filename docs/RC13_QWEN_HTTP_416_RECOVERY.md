# RC13 Qwen3 LiteRT completed HTTP Range verification

## Pixel 10 Pro / Android 17: confirmed actual cause

In public RC12 the Qwen3_1.7B.litertlm journal reports:
- state=failed, attempts=4, Job-ID=552669068, Stop-Reason-Code=-1
- HTTP 416; local .part offset=2056729520 bytes, remote Content-Range is absent
- network validated, INTERNET=true, Wi-Fi=true, VPN=true, metered=false

The original Hugging Face model HEAD (no CDN redirect) returns HTTP 302,
X-Linked-Size=2056729520 and X-Linked-ETag
362ec1c906a49bbe1e68b118fdb789b70a713063b00ac914043cbda03bcf3d9a.
This establishes that remote metadata independently matches the exact
local byte count, not that local file bytes have been verified.

The CDN may send HTTP 416 at EOF without Content-Range/ETag. RC12 required
416 Content-Range bytes */total and fails even with a complete .part.

## Safe fix on existing ModelStore

When a resumed HTTP Range starts at local EOF and returns 416:
1. Reject contradictory Content-Range if present.
2. Trust a previously pinned SHA-256 only after hashing all actual local bytes.
3. Otherwise make a bounded HTTPS HEAD on the original trusted source. A
   huggingface.co /owner/model/resolve/... source can supply X-Linked-Size
   and SHA-256 X-Linked-ETag. Verify both and hash all local bytes before
   atomic installation.
4. Without trusted SHA, require exact remote file length and current strong
   ETag identical to the saved .part validator; weak ETag/unknown length
   cannot authorize publication.
5. Never treat a rounded UI "100%" as verified completion. Keep partials,
   including rejected/corrupt ones, and installed models intact until
   verification succeeds. On checksum mismatch mark the job FAILED and
   require an explicit user action to retry or discard the partial.

Regression tests cover missing 416 headers, exact size + trusted HF SHA,
wrong size, contradictory range, invalid hash, weak or changed validators.

## Device acceptance after upgrade

Use Resume on the existing 2,056,729,520-byte Qwen partial in Pocket AI
RC13. It should enter verifying, hash local data, atomically install,
and not redownload 2GB. Inspect Android download diagnostic and check
Qwen is listed as installed. Then load and generate a real reply if its
LiteRT package supports the selected device/runtime. Remains OPEN
until verified on device.
