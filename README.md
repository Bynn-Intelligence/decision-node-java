# DecisionNode Java SDK

The official Java and Kotlin client for the [DecisionNode](https://decisionnode.com) API: ask
typed questions about any state and get probabilities back.

Java 17 or later. Works from Kotlin as is. Implements version 1.1 of the DecisionNode SDK
specification, which `DecisionNode.SPEC_VERSION` holds: decisions with `truth`, `choice`, `score`
and `number` questions, `/v1/systemone`, the models list, image uploads, sessions and the Batch
API.

## Install

Gradle:

```kotlin
dependencies {
    implementation("com.bynn:decisionnode:0.1.0")
}
```

Maven:

```xml
<dependency>
  <groupId>com.bynn</groupId>
  <artifactId>decisionnode</artifactId>
  <version>0.1.0</version>
</dependency>
```

The only dependency is Jackson; HTTP and WebSockets go through the JDK's `java.net.http`.

## Set your API key

```sh
export DECISIONNODE_API_KEY="dn_..."
```

## Quick start

```java
import com.bynn.decisionnode.*;
import java.util.Map;
import static com.bynn.decisionnode.Questions.*;

DecisionNode dn = new DecisionNode(); // reads DECISIONNODE_API_KEY

Decision d = dn.decide(DecideRequest.builder()
    .state(Map.of("ticket", "I was charged twice and nobody has replied for 3 days."))
    .question("route", choice("Where should this go?",
        options("billing", "money", "bug", "broken", "account", "login")))
    .question("urgency", score("How urgent is this?", "routine", "today", "urgent", "critical"))
    .question("escalate", truth("Escalate to a human now?"))
    .build());

d.choice("route").choice();          // "billing"
d.choice("route").probabilities();   // {billing=0.87, bug=0.08, account=0.05}
d.score("urgency").score();          // 2.31
d.truth("escalate").truth();         // 0.94
```

Kotlin:

```kotlin
import com.bynn.decisionnode.DecisionNode
import com.bynn.decisionnode.Questions.choice
import com.bynn.decisionnode.Questions.truth

val dn = DecisionNode()

val d = dn.decide(
    mapOf("ticket" to "I was charged twice and nobody has replied for 3 days."),
    mapOf(
        "route" to choice("Where should this go?", mapOf("billing" to "money", "bug" to "broken")),
        "escalate" to truth("Escalate to a human now?"),
    ),
)

d.truth("escalate").truth()   // 0.94
d["route"]                    // Answer: TruthAnswer, ChoiceAnswer, ScoreAnswer or UnknownAnswer
```

## Questions

| Builder | Answer | Read |
|---|---|---|
| `truth(instructions)`, `truth(instructions, ifTrue, ifFalse)` | `TruthAnswer` | `truth()`: probability it is true |
| `choice(instructions, options)` | `ChoiceAnswer` | `choice()`, `confidence()`, `probabilities()` by option name |
| `score(instructions, levels)` | `ScoreAnswer` | `score()`, `confidence()`, `probabilities()` and `legend()` by level index |
| `number(instructions)`, `number(instructions, min, max)`, `number(instructions, min, max, step)` | `NumberAnswer` | `number()`, `expected()`, `confidence()`, `probabilities()` by the value as the API wrote it (`"7"`, `"3.5"`) |

- `state`, instructions, descriptions and levels take a string or any value Jackson can
  serialise: maps, lists, records, POJOs.
- `choice` takes a map of option name to description, a list of names, or names as
  arguments. Option names are sent and returned exactly as written. Use `options(...)` or a
  `LinkedHashMap` to keep their order.
- `score` levels run from low to high.
- `number` asks for a value on the grid `min`, `min + step`, ... up to `max`. A bound left null
  is left out of the request, so the API's default applies (0, 255 and 1). A grid holds at most
  4,096 values. Pass a `BigDecimal` to send an exact decimal.
- A plain map in the API's wire form is accepted wherever a builder is, so a request copied
  from the API reference runs unchanged.

Answers are read by question id: `d.truth("id")`, `d.choice("id")`, `d.score("id")`, or
`d.get("id")` and `d.answers()` for the generic `Answer`. `Answer` is a sealed interface, so a
`switch` or Kotlin `when` over it is exhaustive. An answer of a type newer than this SDK comes
back as `UnknownAnswer` with its fields in `raw()`. So does an answer of a known type whose
fields are missing or of the wrong type; the rest of the decision is read as usual. Every
`truth` answer is a `TruthAnswer`, whichever path sent it (`/v1/systemone` included).

Numbers are exactly what the API sent: no rounding and no renormalising.

A `Decision` also carries `model()` (the version that answered), `usage()`, `requestId()`,
`latencyMs()`, `safety()` and `raw()`, the parsed body.

## The client

| Method | Does |
|---|---|
| `decide(state, questions)`, `decide(state, questions, images, model)`, `decide(DecideRequest)` | One decision |
| `decideMany(requests)`, `decideMany(requests, concurrency)` | Many decisions, at most `concurrency` (default 8) at a time. One `DecisionResult` per request, in input order; one failure does not stop the others. Every request is checked first: a `concurrency` below 1 or an invalid request (an empty question map, a value that cannot be serialised) throws `IllegalArgumentException` and nothing is sent, not even the valid requests. |
| `check(state, instructions)`, `check(state, instructions, ifTrue, ifFalse)`, `check(state, instructions, ifTrue, ifFalse, images, model)` | One `truth` question; returns the `TruthAnswer` (see below for a malformed answer) |
| `classify(state, instructions, options)`, `classify(state, instructions, options, images, model)` | One `choice` question; returns the `ChoiceAnswer` |
| `score(state, instructions, levels)`, `score(state, instructions, levels, images, model)` | One `score` question; returns the `ScoreAnswer` |
| `number(state, instructions, min, max, step)`, `number(..., images, model)` | One `number` question; returns the `NumberAnswer` |
| `gate(action, context)`, `gate(action, context, images, model)` | Should this action run? See below |
| `systemone(state, questions)`, `systemone(state, questions, images, model)` | One `POST /v1/systemone`, the strict path for `truth`, `choice` and `score`. The model is always sent; a `truth` question goes in the wire type that path takes and comes back as a `TruthAnswer` |
| `upload(images)` | Uploads images now and returns their upload ids; see Images |
| `openSession(request)`, `supervise(request, feed, onReply)` | Sessions; see below |
| `batches()`, `runBatch(requests)` | The Batch API; see below |
| `models()` | The models and their published limits, as `ModelInfo` entries |
| `decideRaw(body)` | Sends the body exactly as given, unchecked, and returns the parsed response, for API fields this SDK does not know yet |

Every method has an `...Async` form returning a `CompletableFuture`:

```java
CompletableFuture<TruthAnswer> f = dn.checkAsync(email, "Is this email a refund request?");
f.thenAccept(a -> System.out.println(a.truth()));
```

In the helpers, as in `decide`, `images` may be null and a null or empty `model` means the
client's.

`check`, `classify`, `score` and `number` promise a typed answer. When their own answer is missing or
malformed, they throw `ServerError` with `status()` 200, the `requestId()` and `reason()` from
the `x-decisionnode-error` header. `decide` returns the same answer as an `UnknownAnswer`.

A client is thread-safe and reuses its connections (one HTTP/2 connection over TLS carries every
call): create one and share it.

```java
List<DecisionResult> results = dn.decideMany(List.of(
    DecideRequest.of(ticketA, Map.of("spam", truth("Is this spam?"))),
    DecideRequest.of(ticketB, Map.of("spam", truth("Is this spam?")))));

for (DecisionResult r : results) {
    if (r.isSuccess()) {
        System.out.println(r.decision().truth("spam").truth());
    } else {
        System.out.println(r.error().getMessage());
    }
}
```

### gate

`gate(action, context)` sends the state `{"action": ..., "context": ...}` with three questions
whose wording is fixed and the same in every DecisionNode SDK:

| Id | Type | Question |
|---|---|---|
| `risk` | score | "How much harm could this action cause if it is wrong?", levels `none`, `low`, `moderate`, `high`, `severe` |
| `touches_production` | truth | "Does this action change a production system, production data or anything customers use?" |
| `verdict` | choice | "Should this action run as it is, wait for a person to confirm it, or be blocked?": `allow` "safe to run without asking", `confirm` "a person should confirm it first", `block` "should not run" |

```java
Decision g = dn.gate("DROP TABLE invoices;", Map.of("environment", "production"));
g.choice("verdict").choice();   // "block"
```

## Images

Give an image by URL when you can. It is the fastest form: nothing is uploaded, and the API
fetches it directly.

```java
Decision d = dn.decide(
    Map.of("claim", Map.of("total", "740.00")),
    Map.of("matches", truth("Does the receipt total equal the claim?")),
    List.of(Image.fromUrl("receipt", "https://example.com/receipt.jpg")),   // recommended
    null);
```

Every form:

| Form | Sent as |
|---|---|
| `Image.fromUrl(id, url)` | `{"id", "url"}` as given; never fetched by this client. `https` only, at most 2,048 characters |
| `Image.fromPath(id, path)`, `Image.fromBytes(id, bytes[, mediaType])`, `Image.fromBase64(id, text[, mediaType])`, `Image.fromStream(id, in[, mediaType])` | Checked here, then inline as base64 or uploaded, by the threshold below |
| `Image.fromUpload(id, uploadId[, mediaType])` | `{"id", "upload_id"}` as given |

Local bytes are checked before anything is sent: JPEG, PNG or WebP by their first bytes (a media
type you give must match them), at most 10 MB, at most 40 megapixels as read from the image header
(the image is never decoded), at most 4 images in a request, and ids unique. A fault raises the
API's own `BadRequestError` with no request made. A file is read when the `Image` is made, so a
file that cannot be read throws `IllegalArgumentException` there.

When a request's local images total under the inline threshold (`DecisionNode.DEFAULT_INLINE_THRESHOLD`,
524,288 bytes; `inlineThreshold(bytes)` on the builder changes it), they go inline as base64 in the
request. At or above it, they are uploaded first: one `POST /v1/uploads` for all of them, then one
`PUT` per image to storage, in parallel, carrying exactly the headers the API returned (never your
API key, never your custom headers), and the request names them by upload id. Uploaded bytes are
remembered in the client's memory by their SHA-256 for the upload's life (until 5 minutes before
it expires, at most 256 entries), so the same bytes in a later call, in `decideMany`, in a session
frame or in a batch line go by their upload id whatever their size. If the API answers that an
upload this client placed has expired or been used up, the client uploads it again and sends the
request once more. No image is resized or re-encoded.

```java
List<String> ids = dn.upload(List.of(Image.fromPath("scan", Path.of("scan.png"))));  // upload now
dn.decide(state, questions, List.of(Image.fromUpload("scan", ids.get(0))), null);
```

A failed `PUT` is retried on 5xx and connection errors; a storage error it cannot recover from
raises `DecisionNodeError` with the storage status, `errorType()` `upload_failed` and the storage's
error code in `reason()`.

## Sessions

A session answers the same questions about a stream of frames over one WebSocket (the JDK's
`java.net.http.WebSocket`). The server answers the newest frame and skips any that arrived while it
was busy; the next reply lists their `seq` in `dropped()`.

```java
Session s = dn.openSession(SessionRequest.builder()
    .state(Map.of("mission", "Survey the north field"))
    .question("abort", truth("Should the mission be aborted now?"))
    .build());
s.connect();
s.send(Frame.of(Map.of("battery", 0.31, "wind_mps", 11.4)));   // returns the frame's seq: 1

for (SessionEvent e : s.replies()) {          // blocks for each event; ends with SessionClosed
    if (e instanceof Reply r) {
        System.out.println(r.seq() + " " + r.answers().get("abort"));
    } else if (e instanceof FrameError f) {
        System.out.println("frame " + f.seq() + ": " + f.message());
    } else if (e instanceof SessionClosed c) {
        System.out.println("closed " + c.code());
    }
}
s.end();
```

- `openSession` always sends a model (the request's, else the client's). A `429`
  `session_limit_exceeded` is never retried and raises `SessionLimitError`, a `RateLimitError`.
- `connect()` sends the API key in the handshake's `Authorization` header, never in the URL, with
  the client's own headers. A session refused at connect closes at once: its `SessionClosed` is the
  first event.
- `send` numbers frames from 1 unless a frame gives its own `seq`, and never waits for a reply. A
  frame's image follows the inline threshold (a small one inline, a large or cached one by upload
  id); frames take no URL image.
- Replies and frame errors are values; `nextEvent(timeout)` and `nextEventAsync()` read one at a
  time. Pings are answered automatically.
- `SessionClosed.reopen()` is true for the codes after which a new session should be opened
  (`1001`, `1006`, `1011`, `4404`, `4408`, `4409`, `4410`). For `1013` it is false: the session is
  kept, so connect to it again after a backoff.
- `end()` ends the session; one that has already ended is success.

`supervise(request, feed, onReply)` runs a long-lived feed for you: it opens a session, sends each
reading, hands every reply and frame error to `onReply`, reopens on the codes above (after a capped
backoff from 1 s to 30 s with jitter; at once for `4408` and `4410`), connects again on `1013`, and
stops on any other code. When the feed ends it waits for the outstanding replies, ends the session
and returns the last `SessionClosed` (null when none was seen); when it stops on a code, it returns
that one.

## Batches

The Batch API answers decisions within 24 hours at half the price.

```java
BatchRun run = dn.runBatch(List.of(
    BatchRequest.builder("ticket-1").state("I was charged twice.")
        .question("refund", truth("Is a refund asked for?")).build(),
    BatchRequest.builder("ticket-2").state("How do I export my data?")
        .question("refund", truth("Is a refund asked for?")).build()));

try (BatchResults results = run.results()) {
    for (BatchResult r : results) {
        if (r.isSuccess()) {
            System.out.println(r.customId() + " " + r.decision().truth("refund").truth());
        } else {
            System.out.println(r.customId() + " " + r.status() + " " + r.error());
        }
    }
}
```

`runBatch(requests, pollInterval, timeout)` creates an empty draft, adds the requests (in calls of
at most 10 MiB), finalizes it, polls at `pollInterval` (default 60 s, at least 10 s; the server's
`Retry-After` when it is longer) until the batch ends, then opens its results. Past `timeout`
(default 26 hours) it raises `TimeoutError`. If adding fails, the draft is cancelled and the error
raised.

The calls one by one are on `dn.batches()`: `create`, `add`, `finalize`, `get`, `list(offset, limit)`,
`results` and `cancel`, each with an `...Async` form. Results are read line by line as you iterate,
so a large file is never held whole; a line's error is a value (`BatchResult.error()`) of the class
its status gives, never thrown. `create`, `add` and `finalize` are retried only on `429` and `529`,
since a repeat after a lost answer could create a second draft; a finalize answered `409` is
checked with `get`, and the batch is returned when an earlier finalize went through.

## Safety

Every decision from the hosted API carries the result of its safety check:

```java
Safety s = d.safety();
s.checked();    // true
s.harm();       // 0.031
s.selfHarm();   // 0.004
s.flagged();    // categories above your key's thresholds, e.g. ["harm"]
```

When the check did not run, `checked()` is false and `reason()` says why. A self-hosted server
sends no safety header and `safety()` is null.

To also get the check in the response body, send the request header
`x-decisionnode-safety-body: true`. Set it once on the client and it goes with every request:

```java
DecisionNode dn = DecisionNode.builder()
    .header("x-decisionnode-safety-body", "true")
    .build();

Decision d = dn.decide(state, questions);
d.raw().get("safety");   // the check, as the body carries it
```

A refused request throws `RefusalError` and is never retried.

## Errors

Every error the API or the network causes is an unchecked `DecisionNodeError`, the exported
base class of every error below, so `catch (DecisionNodeError e)` catches them all. `getMessage()`
is the server's message (`detail.message`, or `detail` when it is a string; `""`, never null,
when the response carried none), or the client's own description when no response arrived. The error also has `status()`,
`errorType()`, `requestId()` and `reason()`, the server's explanation in words from the
`x-decisionnode-error` header.

Only a 2xx response is a success. A 2xx whose body is not JSON is a `ServerError`.

| Status | Error | Extra |
|---|---|---|
| 400 | `BadRequestError`; `ContextLimitError` when the input is longer than the model accepts | |
| 401, or no key | `AuthenticationError` | |
| 402 | `InsufficientCreditError` | |
| 403 refusal | `RefusalError` | `category()` (`harm` or `self_harm`), `safety()` |
| 403 other | `PermissionDeniedError` | |
| 404 | `NotFoundError` | |
| 422 | `ValidationError` | `entries()` |
| 429 | `RateLimitError`; `SessionLimitError` (a subclass) for `session_limit_exceeded` | `retryAfter()` in seconds |
| 529 | `OverloadedError` | `retryAfter()` in seconds |
| other 5xx, a 2xx that is not JSON, or a helper's own answer missing or malformed | `ServerError` | |
| any other status, 3xx included | `DecisionNodeError` itself, with `status()` | |
| no response | `ConnectionError`, `TimeoutError` | |

```java
try {
    dn.decide(state, questions);
} catch (RateLimitError e) {
    System.out.println("retry in " + e.retryAfter() + " s, request " + e.requestId());
} catch (DecisionNodeError e) {
    System.out.println(e.status() + " " + e.reason());
}
```

The async forms complete exceptionally with the same errors.

A request the API would refuse for a fixed rule fails before anything is sent, with the API's own
class, error type and message and no status or request id: an image that is not JPEG, PNG or WebP,
over 10 MB or over 40 megapixels, more than 4 images, a URL that is not `https` or is over 2,048
characters, a choice of no or more than 255 options, a score of more than 10 levels, a `truth`
question with neither instructions nor criteria, or a number question without instructions raise
`BadRequestError`; a number grid that cannot be built (`min` above `max`, `step` not above 0,
more than 4,096 values) or a score without levels raise `ValidationError` with the API's entries.
In `decideMany` one such request fails the whole call and nothing is sent. Token limits depend on
the model and stay the server's to judge.

A problem with your own arguments is the JDK's `IllegalArgumentException`, thrown before
anything is sent: an empty question map, a value that cannot be serialised to JSON, an image
file that cannot be read, a `decideMany` concurrency below 1 or an invalid request among those
given to `decideMany` (nothing is sent), a `runBatch` poll interval under 10 seconds, and, when
the client is built, a
timeout that is not above 0, a negative `maxRetries`, a base URL that is not `https` (see
Configuration), or an `HttpClient` that follows redirects, is not the JDK's own or turns off TLS
certificate or hostname checks (see Your own HttpClient). These are never wrapped in a
`DecisionNodeError`.

## Retries and timeouts

- Each attempt has a 10 second timeout. It covers the whole attempt, from sending the request
  to the last byte of the response body, so a response that arrives slowly still ends on time.
- Failed attempts are retried twice on 408, 429, any 5xx (529 included), timeouts and
  connection errors. The wait is the server's `retry-after-ms`, else `Retry-After` in seconds
  or as an HTTP date, capped at 60 s; without one, an exponential backoff from 0.5 s with
  jitter, capped at 8 s. A decision changes nothing on the server, so a retry is always safe.
- 400, 401, 402, 403, 404 and 422 are never retried.
- Batch `create`, `add` and `finalize` are retried only on 429 and 529, and opening a session is
  never retried on `session_limit_exceeded`; see their sections.
- Batch results are read as they stream: the timeout covers the wait for the response to begin.
- Redirects are never followed, so the API key is never sent to another URL or over plain
  HTTP. A 3xx is a `DecisionNodeError` with its status. An `HttpClient` passed to the builder
  must not follow redirects either; see below.

## Configuration

```java
DecisionNode dn = DecisionNode.builder()
    .apiKey("dn_...")                          // default: DECISIONNODE_API_KEY
    .baseUrl("https://api.decisionnode.com")   // default: DECISIONNODE_BASE_URL, else this
    .model("decisionnode-flash-latest")        // default: DECISIONNODE_MODEL, else decisionnode-latest
    .timeout(Duration.ofSeconds(10))           // per attempt, above 0
    .maxRetries(2)                             // 0 or more
    .inlineThreshold(524_288)                  // bytes; local images under it go inline
    .header("x-decisionnode-safety-body", "true")
    .httpClient(HttpClient.newHttpClient())    // a proxy, an executor, TLS settings; see below
    .build();
```

An argument wins over the environment, which wins over the default. A null or empty string
counts as not given.

The base URL must be `https`, so the API key never travels unencrypted. Plain `http` is
accepted only for a server on the same machine (`localhost`, `127.0.0.1` or `::1`), for example
a self-hosted server during development. Any other `http` URL, from an argument or from
`DECISIONNODE_BASE_URL`, makes `build()` throw `IllegalArgumentException`. A model can also be set per call with `decide(state, questions, images,
model)`, the helpers' `model` argument or `DecideRequest.builder().model(...)`.

| Model | |
|---|---|
| `decisionnode-latest` | The latest model (default) |
| `decisionnode-flash-latest` | The latest fast, lower-cost model |
| `decisionnode-1.0`, `decisionnode-1.0-flash` | Pinned versions |

### Your own HttpClient

`build()` checks an `HttpClient` you pass and throws `IllegalArgumentException` when:

- it follows redirects. Use `HttpClient.Redirect.NEVER`, the JDK default.
- it is not the JDK's own client, made by `HttpClient.newBuilder()` or
  `HttpClient.newHttpClient()`. A subclass can say it never follows redirects and do otherwise,
  so a client the SDK cannot inspect is treated as one that follows them.
- the base URL is `https` and its `SSLContext` does not check certificates. A context other
  than the JDK default is tested once, when the client is built, with an in-memory TLS
  handshake against a throwaway self-signed certificate; one that accepts that certificate, or
  does not clearly refuse it, is refused. A context with your own trust store (a company
  certificate authority, for example) passes.

Hostname checks are on in the JDK's client unless the system property
`jdk.internal.httpclient.disableHostnameVerification` is set; with it set, a client for an
`https` base URL is refused, whether you pass an `HttpClient` or not. Plain `http` to
`localhost`, `127.0.0.1` or `::1` uses no TLS, so these TLS checks do not apply to it.

The SDK sends no telemetry and talks to nothing but the base URL and, for an image upload, the
storage URL the API returns for it. Its only added header is
`User-Agent: decisionnode-java/<version>`. Turn on debug logging for the
`com.bynn.decisionnode` `System.Logger` to see the method, path, status, request id and
timing of each attempt; states and questions are never logged.

## Development

```sh
./gradlew build                                         # unit tests
DECISIONNODE_CONFORMANCE_DIR=/path/to/suite ./gradlew conformance
```

The conformance suite is the language-neutral set of cases every DecisionNode SDK passes; it
needs Python 3.10 or later for its mock server. Every case runs twice, through the blocking
methods and through the `...Async` methods. Without `DECISIONNODE_CONFORMANCE_DIR` the
conformance task fails rather than skipping. The suite's mock serves uploads, sessions (a
WebSocket) and batches as well; this SDK passes every case of suite 1.4 in both forms. CI checks the suite out at a pinned commit, set
once as `CONFORMANCE_REF` in `.github/workflows/ci.yml`; every action there is pinned to a
commit SHA and no checkout keeps its credentials.

## License

MIT. Copyright (c) 2026 Bynn Intelligence, Inc.
