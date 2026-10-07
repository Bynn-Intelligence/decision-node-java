# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [0.1.0] - Unreleased

First release of the DecisionNode SDK for Java and Kotlin.

### Added

- `DecisionNode` client with `decide`, `decideMany`, `check`, `classify`, `score`, `gate`,
  `models` and `decideRaw`, each in a blocking form and an `...Async` form returning a
  `CompletableFuture`. `check`, `classify`, `score` and `gate` take images and a model like
  `decide`. `gate` asks three questions with fixed wording, the same in every DecisionNode SDK.
- `DecisionNode.SPEC_VERSION`, the version of the SDK specification implemented: `"1.0"`.
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
