# ROSELINK legacy find actions: exact source contracts

This slice completes the eight named source configurations whose find widgets remained outside
Coral and RSv2: Explorer(F8); Cambrian, OpenFun, OpenLink, SportFeel and SWIFT(RS Common);
HeadFeel and source-qualified EarFree i5(ZT). It reuses their existing adapter IDs/transports and
global unique manufacturer qualification. Name-only i5 keeps the previous battery/noise path.
Generic protocol configurations, AURAMIC and other models do not gain this action.

## Source evidence

Original local APK AOT source root: `.analysis/roselink-coral/aot-output/asm/` in the original
research checkout. Existing source inventory and attribution remain unchanged; no APK code/assets
are copied. Actual config constructors allocate RDeviceFindItemWidgetModel for these eight models.

- `roselink_bluetooth/commander/sender/f8_sender.dart`, F8Sender::setFindDevice0x7f6870:
  outgoing `00 2A 01 00 01 target`; left1, right2, both3, STOP0.
- `sender/rs_common_sender.dart`, RsCommonSender::setFindDevice0x7f6914:
  outgoing template `FF 00 02 2F target AA`; left1, right2, both3, STOP4.
  The existing RS Common frame encoder supplies sequence and checksum exactly as for other
  established family writes. This is not borrowed RSv2 framing or a fabricated read query.
- `sender/zt_sender.dart`, left0x80dc64/right0x80c154: i5(Zt229) uses0E82 and
  HeadFeel(Zt231) uses0E02, payload `[0,1]` for left/headset or `[1,1]` for right.
  Zt229::setFindOff0x7f6a0c and Zt231::setFindOff0x7f6a5c use `[3,0]`, with the existing
  ZT length/checksum format. The inherited both getter0x7f6bc0 simply delegates to left;
  no separate dual-bud start is claimed. i5 exposes left/right only; HeadFeel exposes headset.
- The complete F8/RS Common receiver dispatch tables have no find-state branch. The actual
  `roselink/modules/device_control/view/normal_ear_find.dart` page invokes RCommander start
  and immediately updates local DeviceController flags, e.g.0xb2bf74–0xb2bf9c. Its dispose
  path0xc256c4 schedules local resets, not a read. There is no proven read-only find GET.
- ZTReceiver::_handleEarFind0xf12868 receives0E02. The i5-specific
  `roselink/rose_bluetooth/receive_handler/zt/earfree_i5_receive_handler.dart`,
  findMeReceived0xea7564, explicitly clears both flags when the reported pair is `[0,0]`
  (conditional branch0xea77cc–0xea781c). Other pairs update mutually dependent local flags;
  they are not imported as fabricated authoritative ringing booleans. HeadFeel's handler
  does not override this function. Only the exact i5 terminal tuple ends an owned action.

## Admission, intent and cleanup

`findAvailable` is added to each existing Legacy/ZT settings feature, published only after an
actual valid ordinary protocol report from an eligible immutable profile. Manufacturer/name
metadata alone, malformed packets and a standalone find acknowledgment do not grant admission.
No find START/STOP or speculative GET occurs during discovery/handshake/refresh.

Family-specific typed Find requests pin expected model, positive UI lease and a finite target.
The shared `RoseFindWrittenIntent` is explicitly local successful-write ownership, not device
confirmation. Encoding does not publish intent or arm a timer. Only `onControlWritten` commits it
and schedules existing OwnedProtocolCleanup for30seconds; its internal generation is independent
of the UI lease. Starts are blocked while an intent is active. A stale UI STOP cannot terminate a
new lease. Source STOP remains available for an owned intent without another report.

Explicit written STOP, exact i5 terminal report, reset, graceful old-channel release and matching
runtime deadline consume ownership. Runtime expiry/graceful cleanup clear local intent without
claiming that a remote STOP was acknowledged. No retry/poll loop is introduced. Abrupt transport
loss cannot guarantee delivery to disconnected hardware and is not reported as a confirmed stop.

Existing Legacy/ZT advanced editors, including their native Settings entry, share a small UI action
controller. Labels say “发送查找请求” and, after successful-write state arrives, “请求已写入，设备未确认”.
The page issues no automatic start. Close and manual stop target only its own lease under the
current address/model/token/owner guards. UI30second cleanup is a secondary backstop; Bluetooth-owned
cleanup is primary and remains effective if the UI process disappears. It is not an on-device timer.

Literal family frame tests and all-eight registry/report-gate tests cover missing/malformed reports,
unsupported profiles, preserved old i5 route, typed IPC, no optimistic intent, stale leases,
manual/timeout/graceful cleanup, reset, and exact i5 terminal notifications. UI tests cover source
finite target lists, write-state feedback and ownership/session cleanup. No phone actions or
physical ringing/stop verification were performed.
