# DecisionNode Java SDK

The official Java and Kotlin client for the [DecisionNode](https://decisionnode.com) API: ask
typed questions about any state and get probabilities back.

Java 17 or later. Works from Kotlin as is. Implements version 1.0 of the DecisionNode SDK
specification, which `DecisionNode.SPEC_VERSION` holds.

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

The only dependency is Jackson; HTTP goes through the JDK's `java.net.http`.

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

- `state`, instructions, descriptions and levels take a string or any value Jackson can
  serialise: maps, lists, records, POJOs.
- `choice` takes a map of option name to description, a list of names, or names as
  arguments. Option names are sent and returned exactly as written. Use `options(...)` or a
  `LinkedHashMap` to keep their order.
- `score` levels run from low to high.
- A plain map in the API's wire form is accepted wherever a builder is, so a request copied
  from the API reference runs unchanged.

Answers are read by question id: `d.truth("id")`, `d.choice("id")`, `d.score("id")`, or
`d.get("id")` and `d.answers()` for the generic `Answer`. `Answer` is a sealed interface, so a
`switch` or Kotlin `when` over it is exhaustive. An answer of a type newer than this SDK comes
back as `UnknownAnswer` with its fields in `raw()`. So does an answer of a known type whose
fields are missing or of the wrong type; the rest of the decision is read as usual.

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
| `gate(action, context)`, `gate(action, context, images, model)` | Should this action run? See below |
| `models()` | The models and their published limits, as `ModelInfo` entries |
| `decideRaw(body)` | Sends the body exactly as given and returns the parsed response, for API fields this SDK does not know yet |

Every method has an `...Async` form returning a `CompletableFuture`:

```java
CompletableFuture<TruthAnswer> f = dn.checkAsync(email, "Is this email a refund request?");
f.thenAccept(a -> System.out.println(a.truth()));
```

In the helpers, as in `decide`, `images` may be null and a null or empty `model` means the
client's.

`check`, `classify` and `score` promise a typed answer. When their own answer is missing or
malformed, they throw `ServerError` with `status()` 200, the `requestId()` and `reason()` from
the `x-decisionnode-error` header. `decide` returns the same answer as an `UnknownAnswer`.

A client is thread-safe and reuses its connections: create one and share it.

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

```java
Decision d = dn.decide(
    Map.of("claim", Map.of("total", "740.00")),
    Map.of("matches", truth("Does the receipt total equal the claim?")),
    List.of(
        Image.fromPath("receipt", Path.of("receipt.jpg")),       // read and base64-encoded
        Image.fromBytes("photo", bytes),                         // media type from the bytes
        Image.fromBytes("logo", logoBytes, "image/png"),
        Image.fromUrl("scan", "https://example.com/scan.webp")), // https only
    null);
```

JPEG, PNG and WebP are accepted. A file is read when the `Image` is made, and its media type
is taken from its first bytes; so is the media type of bytes given without one. A file that
cannot be read, or bytes that are not JPEG, PNG or WebP, throw `IllegalArgumentException`
there. The API sets the limits on the number and size of images; `models()` lists them.

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
| 429 | `RateLimitError` | `retryAfter()` in seconds |
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

A problem with your own arguments is the JDK's `IllegalArgumentException`, thrown before
anything is sent: an empty question map, a value that cannot be serialised to JSON, an image
file that cannot be read or is not an image, a `decideMany` concurrency below 1 or an invalid
request among those given to `decideMany` (nothing is sent), and, when the client is built, a
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

The SDK sends no telemetry and talks to nothing but the base URL. Its only added header is
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
conformance task fails rather than skipping. CI checks the suite out at a pinned commit, set
once as `CONFORMANCE_REF` in `.github/workflows/ci.yml`; every action there is pinned to a
commit SHA and no checkout keeps its credentials.

## License

MIT. Copyright (c) 2026 Bynn Intelligence, Inc.
