# Foxy engine — verification report

Everything below was produced by running the engine, not by reading the code.

## What is covered automatically

| Suite | Command | Needs network |
| --- | --- | --- |
| `FoxyEngineFakeEdgeTest` | `./gradlew :app:testDebugUnitTest --tests '*FoxyEngineFakeEdgeTest*'` | no |
| `FoxyEngineLiveTest` | `FOXY_LIVE_TEST=1 ./gradlew :app:testDebugUnitTest --tests '*FoxyEngineLiveTest*'` | yes |
| `FoxyScreenshotTest` | `./gradlew :app:testDebugUnitTest --tests '*FoxyScreenshotTest*'` | no |

`FoxyEngineFakeEdgeTest` runs a local HTTP/2-over-TLS server that answers `CONNECT` with `200`,
echoes `DATA` back and replenishes the flow-control windows. It proves the relay path the user
feels: status handling, bidirectional data, 200 KB payload chunking, `END_STREAM`, and that no
frame larger than the peer `MAX_FRAME_SIZE` (16 KiB) ever leaves the engine.

`FoxyEngineLiveTest` drives the real `FoxyH2Session` against Mozilla's real Fastly edges
(`muc139.m1.fastly-masque.net:2499`).

## Bugs found by actually running the engine (fixed in 2.3.1-foxy9)

1. **ALPN was never negotiated.** Android's `SSLSocket` has no `setApplicationProtocols()` and
   Conscrypt silently ignores `SSLParameters.setApplicationProtocols`, so the ClientHello carried
   no ALPN extension and every edge answered `ALPN=none` — the engine then refused its own
   connection (`did not negotiate HTTP/2`). Fixed by going through Conscrypt's own
   `setApplicationProtocols(SSLSocket, String[])` (both `org.conscrypt` and
   `com.android.org.conscrypt`), with the socket method and `SSLParameters` as fallbacks.
2. **The connection `WINDOW_UPDATE` had a 5-byte payload.** The edge answered
   `GOAWAY FRAME_SIZE_ERROR` immediately after the preface, so no stream could ever be opened.
   Now a valid 4-byte increment.
3. **`DATA`/`HEADERS` ignored the peer `MAX_FRAME_SIZE`.** Any relay buffer larger than 16 KiB
   tore the connection down mid-transfer. Frames are now chunked, and an oversized header block is
   sent as `HEADERS` + `CONTINUATION`.

## Latest live run (2026-11-27)

```
onlyConscryptHelperNegotiatesH2                 PASS  (SSLParameters alone: ALPN=null, Conscrypt helper: h2)
connectorReachesTheEdgeWithBundledRoots         PASS  connected=true canOpen=true
everyHelloSplitModeCompletesTheHandshake        PASS  OFF / SNI / FIRST_BYTE / RECORD
connectStreamSendsAWellFormedRequest            PASS  no pass -> refused locally; bogus pass -> HTTP 400
severalConcurrentConnectStreamsStayHealthy      PASS  3 streams -> 3x HTTP 400, session healthy
port443IsRejectedInsteadOfFaked                 PASS  rejected: certificate is not valid for the edge host
```

TLS chain accepted with only the bundled roots: `*.m1.fastly-masque.net` →
`GlobalSign GCC E46 OV TLS CA 2025` → `GlobalSign Root E46`.

## Still requires a phone

* A real `CONNECT 200` and end-to-end throughput need a valid Guardian proxy pass (FxA login).
* `VpnService` establishment and the `lanShareUpstreamPort()` Foxy case cannot run in a unit test.
