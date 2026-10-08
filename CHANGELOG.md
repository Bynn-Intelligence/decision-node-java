# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [0.2.0] - Unreleased

Implements version 1.2 of the DecisionNode SDK specification: sixteen images and videos as frames.

### Added

- Videos sent as the frames the caller sampled: `Video` (`Video.of(id, frames)` with `fps`,
  `start` and `end`) and `VideoFrame` (`fromBytes`, `fromBase64`), on `DecideRequest` and
  `BatchRequest` (`video`, `videos`) and as a `videos` argument of `decide`, `systemone`, `check`,
  `classify`, `score`, `number` and `gate` and their async forms. A map in the wire form is accepted wherever a `Video` or a `VideoFrame` is, and
  checked by the same rules. Frames always go inline: they are never uploaded, cached, resized or
  re-encoded.
- The API's video rules are checked before anything is sent, with the API's own message: at most
  4 videos, an id that is present and unique across images and videos, at least one frame, frame
  times at least 0 and strictly increasing, a JPEG, PNG or WebP media type for every frame, `fps`
  above 0 and at most 4, `start` and `end` at least 0 and `end` after `start`. The number of
  frames, their sizes and their pixels are left to the server.
- `Usage.videoFrames()` and `Usage.videoSeconds()`, null for a request without videos, and
  `Decision.warnings()`, the server's notes exactly as sent, empty when there are none.

### Changed

- `SPEC_VERSION` is `"1.2"`.
- Up to 16 images a request, all of a request's uploads in one presign of up to 16 files. The 17th
  image is refused locally with "Too many images: 17, the limit is 16.".
- A model's image and video limits held from `models()` are also read where the API publishes
  them, in its `images` and `videos` objects.

## [0.1.0] - Unreleased

First release of the DecisionNode SDK for Java and Kotlin, implementing version 1.1 of the
DecisionNode SDK specification.

### Added in specification 1.1

- `number` questions: the `Questions.number` builder (a bound left null is left out, so the API's
  defaults apply), `NumberAnswer` with `number`, `expected`, `confidence` and `probabilities` keyed
  by the value as the API wrote it, the `number` helper, and `Decision.number(id)`.
- Every `truth` answer is a `TruthAnswer`, whichever path sent it.
- `systemone`, the strict `/v1/systemone` path, with the model always sent.
- Image URLs are the recommended form and pass through after a local check (`https`, at most
  2,048 characters). `Image.fromBase64`, `Image.fromStream` and `Image.fromUpload` join the
  existing forms.
- Local bytes are checked before any request: JPEG, PNG or WebP by their magic bytes, at most
  10 MB, at most 4 images, unique ids, and at most 40 megapixels read from the JPEG, PNG or WebP
  header without decoding the image.
- `DEFAULT_INLINE_THRESHOLD` (524,288 bytes) and the `inlineThreshold` builder option: a
  request's local images under it go inline, at or above it they are uploaded with one presign
  and parallel `PUT`s carrying exactly the returned headers. Uploads are cached by SHA-256 in the
  client's memory (256 entries, until 5 minutes before they expire) and reused whatever their
  size; concurrent requests with the same bytes share one upload. A stale upload the client placed
  is uploaded again and the request resent once. `upload(images)` uploads explicitly.
- Every fixed, documented request rule is checked locally, in the server's order, before
  anything is sent, and raises what the API would answer: the same class and `error_type`, the
  documented message, and for a 422 a `ValidationError` with the API's entries. Covered: the
  128-level nesting limit; unknown question types (a `number` question on `systemone` included);
  questions without a type or criteria, and instructions, states, descriptions or levels of the
  wrong type; option and level counts; a `truth` without text; number grids (`float_type`,
  finiteness, 2^53, decimals, the decimal bound, `min` above `max`, 4,096 values) and a missing
  instruction; image count, empty or repeated ids, bytes, size, pixels and an upload reference's
  media type; URLs that are not `https`, over 2,048 characters, with user information, on a port
  other than 443 or on a literal blocked address (`image_url_refused`); session `window` and
  `ttl_seconds`; frames (`Session.send` throws, writes nothing and uses no `seq`); batch
  `custom_id` rules and an empty add; `batches.list` paging. Limits only `/v1/models` states
  (number support, sessions served, `max_window`, `requests_per_batch`, `max_custom_id_chars`) are
  checked once `models()` has been called on the client, and left to the server otherwise.
- A storage `PUT` answered 403 after its URL's 5 minutes (counted from the presign's
  `expires_in`) is presigned again once; a second such 403 raises.
- Sessions over the JDK's WebSocket: `openSession`, `Session.connect`, `send` (frames numbered
  from 1), `replies`, `nextEvent`, `end`, the `Reply`, `FrameError` and `SessionClosed` values,
  `SessionLimitError` (a `RateLimitError`), and `supervise`, which reopens on the reopen codes
  and connects again on `1013`.
- The Batch API on `batches()`: `create`, `add` (split at 10 MiB), `finalize`, `get`, `list`,
  `results` (read line by line) and `cancel`, and `runBatch` for the whole lifecycle.
- `models()` returns the fields of specification 1.1 in `limits`.

- `checkRequest` (for `decide` and the helpers, and for `openSession`), `checkSystemoneRequest`,
  `Batches.checkCreateRequest`, `Batches.checkAddRequest` and `Session.checkFrame` run every local
  check the call would run before sending, held `/v1/models` limits included, and throw what it
  would throw. Nothing is sent or uploaded, and `checkFrame` uses no `seq`.
- Held `/v1/models` limits are found by the entry's name, else by the entry that states the name
  as its `alias` or pinned `version`; a model the list does not state is left to the server.
- `uploadTimeout` (default `DEFAULT_UPLOAD_TIMEOUT`, 60 s): each storage `PUT` has its own timeout,
  apart from the API attempt's.
- Batch results stream line by line with an idle timeout while the reader waits for more data;
  `results` reads the whole file however many lines it holds. A stalled read raises
  `TimeoutError`.
- The session's WebSocket is opened by the client's `HttpClient`, so it uses its proxy and
  `SSLContext`.

### Changed in specification 1.1

- `SPEC_VERSION` is `"1.1"`.
- Image bytes that are not JPEG, PNG or WebP raise `BadRequestError` before any request, no
  longer `IllegalArgumentException` when the `Image` is made. A batch `add` with no requests
  raises `BadRequestError` (`api_usage_error`) rather than `IllegalArgumentException`. A file that cannot be read still
  throws `IllegalArgumentException` where the `Image` is made.
- `Answer` permits `NumberAnswer`: an exhaustive `switch` or Kotlin `when` over it gains a
  branch.

### Added in specification 1.0

- `DecisionNode` client with `decide`, `decideMany`, `check`, `classify`, `score`, `gate`,
  `models` and `decideRaw`, each in a blocking form and an `...Async` form returning a
  `CompletableFuture`. `check`, `classify`, `score` and `gate` take images and a model like
  `decide`. `gate` asks three questions with fixed wording, the same in every DecisionNode SDK.
- `DecisionNode.SPEC_VERSION`, the version of the SDK specification implemented.
- `models()` returns `ModelInfo` entries; `decideMany` returns one `DecisionResult` per request.
  It checks every request before sending any: a concurrency below 1 or an invalid request
  throws `IllegalArgumentException` and nothing is sent.
- `check`, `classify` and `score` throw `ServerError` (status 200, the request id, and the
  reason from `x-decisionnode-error`) when their own answer is missing or malformed; `decide`
  returns such an answer as `UnknownAnswer`.
- Question builders `truth`, `choice` and `score`, and `options` for ordered option maps.
  Wire-form maps are accepted wherever a builder is.
- Typed answers: `TruthAnswer`, `ChoiceAnswer`, `ScoreAnswer`, and `UnknownAnswer` for
  question types newer than the SDK. Numbers are returned exactly as the API sent them.
- Images from a file path, bytes or an `https` URL. A file is read when the image is made;
  the media type of a file, or of bytes given without one, is read from the first bytes.
- An answer of a known type with missing or wrongly typed fields is returned as
  `UnknownAnswer` with its raw fields; the decision does not fail.
- Retries on 408, 429, 5xx, timeouts and connection errors, honouring `retry-after-ms` and
  `Retry-After` (seconds or an HTTP date), capped at 60 s. The 10 second timeout covers the
  whole attempt, from sending the request to the last byte of the body.
- Only a 2xx response is a success. Redirects are never followed. A caller's `HttpClient` that
  follows them, or that is not the JDK's own client and so cannot be shown to refuse them, is
  refused.
- For an `https` base URL, TLS checks must stay on: a caller's `SSLContext` that accepts a
  self-signed certificate (tested once with an in-memory handshake) is refused, and so is any
  client while JDK hostname verification is turned off.
- The base URL must be `https`; plain `http` is accepted only for `localhost`, `127.0.0.1`
  and `::1`.
- Typed errors under the exported base class `DecisionNodeError`. `getMessage()` is the server's message, `""` when
  it sent none; the error also carries the status, error type, request id and the server's
  reason. A status without a class of its own is `DecisionNodeError` itself.
- Problems with the caller's own arguments (an empty question map, an unserialisable value,
  an unreadable or unrecognised image, a timeout not above 0, a negative `maxRetries`, a
  plain-`http` base URL, a concurrency below 1, an invalid request given to `decideMany`, an
  `HttpClient` that is refused as above) throw `IllegalArgumentException` before
  anything is sent.
- The `x-decisionnode-safety-body` request header, documented in the README, puts the safety
  check in the response body as well.
- The safety check of every decision, read from the `x-decisionnode-safety` header.
- Configuration from `DECISIONNODE_API_KEY`, `DECISIONNODE_BASE_URL` and `DECISIONNODE_MODEL`.
  An empty string passed for the key, base URL or model counts as not given.
- CI pins every action to a commit SHA, checks out without keeping credentials, and runs the
  conformance suite at a pinned commit.
