# Coral AB OTA static specification

Source: ROSELINK3.8.2/code5409 APK and existing Dart AOT output described in
[Coral audit](rose-coral-followup.md). No phone changes, server requests or
firmware downloads were performed. This is a reconstructed specification;
remaining unresolved details are explicit below.

## Model and metadata

Coral declares DeviceOtaType.ab. createAbOtaPlan0xb4e2b0 selects AbOta; only
AURAMIC selects AbOtaV2. Normal target is self, metadata tag1. RName.trackingName
0x77d27c returns lowercase coral for this model; attachSession/bindSession passes
that tracking name into metadata keyId. This is a model selector, not a secret.

ApiDeviceService.fetchDeviceOtaByKeyId0xb47b0c posts to the configured official
API origin at /app/ota/v2/get-by-key-id-and-tag. Request keys are keyId, pid, tag,
clientVersion and lang. Normal self uses pid empty, tag1, lang zh/en; clientVersion
comes from SystemService. The call sets token:false. The API wrapper requires
response code string form200 and returns data. DeviceService._buildOtaResult
0xb47798 requires a Map with valid version, maps server otaUrl to internal
otaContent, and retains versionDescribe and encodeData. No request was issued.

hasNewVersionFor0x95c5d8 parses current/latest as decimal integers and requires
positive values, nonempty URL and latest greater than current. This is not
semantic-version comparison. Active session ownership, current version, loaded
server information and transport preparation are separate readiness conditions.

Ordinary AbOta obtains getText firmware content, interpreted as hexadecimal
text. getStartData0xeb7738 indexes pairs at twice the byte offset, parses radix16
and advances byte offset by parsed bytes. No Coral container signature/model
verifier has been established. compareOtaDataWithEncodeData0xb52d34 returns true;
the shared MD5 prefix helper is not a signature verifier. Independent policy
should require nonempty even-length hex, reject nonhex, bound image size, bind
the exact artifact digest to metadata model/target/version, and reject image
replacement during a session. These stronger checks are proposed policy, not
claims about vendor authenticity verification.

## Normal Coral transport and grammar

BtConnector.writeOta0xef7114 writes raw bytes through the SPP OTA connection,
without normal FF transformation. Use the existing single protocol owner to
serialize OTA; ordinary refresh/settings must not interleave. Do not open an
uncoordinated control socket. Coral config control and OTA UUID fields match.

constructData0xb53bd8 emits opcode,sequence,payload; sequence wraps modulo256.
Normal self/TWS startup0xb538a0 sends 91 seq 01 and arms a6-second watchdog.
RX91 seq01 with exactly2valuebytes triggers getInfoUpdate0xeb7edc once; it sends
91 nextSeq02 latestVersionLE16 `MD5(imageBytes)[0:4]`. Only then expect parameter
response91 seq02. Vendor masks version to16bits and substitutes65535 for invalid
text; independent policy should reject invalid/out-of-range metadata instead. A10-second callback retries startup if91/02 never arrived; it
is not a getInfo delay. The identification/GUID-forward sequence belongs other
routes selected by _usesAbGuidForward0xb552d8, not ordinary Coral. Preparation
query sendGetAllInfo0xb54a1c emits 92 seq 01 00 03 00 04 00 06 00.

- 92 seq responses contain TYPE,LENGTH,VALUE records; LENGTH counts VALUE only.
- 91 seq type responses contain the remaining value directly. Type02 needs
  eleven bytes: offset32LE at0, blockSize32LE at4, packetSize16LE at8 and
  allowedUpdate at10, exactly1. Compiled size loads are signed.
- 90 seq status carries optional status-specific bytes. It is not90status.
  The parser strips opcode only and the handler reads remaining byte1 as status.

## Blocks

The transfer method0xeb5a64 saves byteOffset and sequence as its retry checkpoint.
Block length is bounded by remaining image and advertised block size. Construct
A0 seq offsetLE32 lengthLE32 and append block bytes into one logical stream.
SPP budget P uses positive device packetSize, otherwise240. First write is the
first P bytes of that stream. Subsequent writes contain20 nextSeq followed by
up to P-2 remaining bytes; sequence advances per packet. Await each write.
After all writes settle, arm the5-second ACK timer; never overlap block writers.

Reject impossible packet budgets, nonpositive block sizes, out-of-image offsets
and arithmetic overflow. The 0xFFFFFFFF block-size sentinel still needs signed
load reconciliation. Keep pending and committed progress separately: the vendor
advances its cursor during construction, but independent UI should commit only
after valid ACK. Save identical block bytes and starting sequence for retry.

## Recovery and terminal states

Status00 cancels ACK timeout and resets the block retry counter; send another
block if bytes remain. For Coral, sending all bytes is not completion: waitFF.
FF is success. 80 terminates on TWS disconnection without retry. 01/02/03/0B/41
are terminal errors. 40 requests retry/resync while active with bytes remaining;
response sequence is logged rather than treated as a new cursor. FD/FE are explicitly logged as device pause/continue not handled and ignored
by this vendor build; they do not acknowledge the block or imply success.

`_retryOrResync0xeb4d8c` defers recovery while a write is in flight. One level-1
retry restores saved offset/sequence and repeats the block from A0. Exhaustion
escalates to handshake. `_restartFromHandshake0xb540a4` allows at most2 restarts by
default, cancels timers, clears response/cache and resets sequence/offset while
retaining the image; renegotiate device parameters before resuming. Do not trust
a stale local cursor across reconnect. Handshake watchdog is6seconds (Duration
e20461); block ACK is5seconds (e201f1), armed after sending. A generation-scoped
independent deadline avoids copying overlapping vendor startup callbacks.

Dispose0xeeea34 invalidates ownership, resets state, cancels timers/subscriptions
and removes its listener. No wire abort opcode has been established. Cancel
must stop future writes and settle an in-flight operation; it does not imply
rollback. Ignore asynchronous results from old session/device generations.
Success cancels timers and enters same-device reconnect; socket loss is not
success and reconnect/version readback is a separate final state.

## Implementation and verification plan

Keep a dedicated typed OTA session inside the protocol owner: Idle,
MetadataReady, ImageReady, AwaitParameters, WritingBlock, AwaitBlockAck,
RestartHandshake, AwaitCompletion, Reconnecting, Complete, Failed, Cancelled.
Inputs are bound metadata/artifact, response bytes, monotonic time and write
completion; outputs are bounded writes, deadlines, progress and terminal events.
Explicit UI start is separate from metadata lookup. Normal DD parsing and raw
OTA parsing must be selected by ownership/state, not a loose prefix search.

Reconstructed tests should cover first/continuation boundaries, sequence wrap,
short final block, identical retry, malformed parameters, ownership changes,
ACK before write completion, timeout limits, final00 waitingFF, FF only once,
80 terminal, cancel with late callbacks, target mismatch and invalid hex. These
are static reconstruction fixtures, not captures from the actual headset.

Remaining gaps: parameter sentinel semantics,
RX stream coalescing boundaries, exact same-device reconnect, and API-wide
headers/signing behavior. token:false proves this call omits the account token,
not that the whole HTTP wrapper adds no app headers. No credentials have been
extracted. OTA remains an explicit unfinished feature, not replaced by firmware
version display or silently removed from the goal.

## Additional receive and HTTP wrapper findings

FD/FE resolution: handler0xeb45dc explicitly logs pause/continue as not handled
and returns without acknowledging or changing transfer state. Preserve timeout
bounds; do not guess a new resume command. Type01 handshake response is exactly
two value bytes and gates the once-only metadata request described above.

BtConnector.receiveDataOta callback0xa6e164 converts each SPP input chunk to hex
and forwards it directly to otaValueReceived; no stream accumulation is visible
in this layer. AbOta.process then treats that chunk as one packet. Independent
code must not assume RFCOMM read boundaries are message boundaries. In known
handshake states91/01 has5totalbytes and91/02 has14; ordinary90status has3, but
status-specific extras and multi-record92 require a bounded explicit parser.
Unexpected layouts should fail the OTA session rather than scan blindly for
another apparent opcode in arbitrary payload.

The examined API base headers contain only Content-Type:application/json.
TokenInterceptor has a separate starburst:true branch for business signing;
the OTA metadata call does not set that extra. The metadata call's token:false
is passed through _prepareOptions/checkToken. This supports an anonymous JSON
metadata client without embedded app credentials; no credential extraction or
live endpoint verification was performed. Server reachability and returned
metadata remain untested. User-account business authentication must not be
copied into the firmware flow.
