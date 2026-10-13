# DecisionNode Java SDK

[DecisionNode](https://decisionnode.com) is a decision engine: send it text, JSON or images with a question, and it answers with calibrated probabilities.

The official Java and Kotlin client for the [DecisionNode](https://decisionnode.com) API: ask
typed questions about any state and get probabilities back.

Java 17 or later. Works from Kotlin as is. Implements version 1.3 of the DecisionNode SDK
specification, which `DecisionNode.SPEC_VERSION` holds: decisions with `truth`, `choice`, `score`,
`number`, `point` and `box` questions, up to 16 images and videos sent as frames, boosters and
their evidence, fine-tuned models, the models list, image uploads, sessions and the Batch API.

## Install

Gradle:

```kotlin
dependencies {
    implementation("com.bynn:decisionnode:0.3.1")
}
```

Maven:

```xml
<dependency>
  <groupId>com.bynn</groupId>
  <artifactId>decisionnode</artifactId>
  <version>0.3.1</version>
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
d["route"]                    // Answer: TruthAnswer, ChoiceAnswer, ScoreAnswer, ... or UnknownAnswer
```

## Questions

| Builder | Answer | Read |
|---|---|---|
| `truth(instructions)`, `truth(instructions, ifTrue, ifFalse)` | `TruthAnswer` | `truth()`: probability it is true |
| `choice(instructions, options)` | `ChoiceAnswer` | `choice()`, `confidence()`, `probabilities()` by option name |
| `score(instructions, levels)` | `ScoreAnswer` | `score()`, `confidence()`, `probabilities()` and `legend()` by level index |
| `number(instructions)`, `number(instructions, min, max)`, `number(instructions, min, max, step)` | `NumberAnswer` | `number()`, `expected()`, `confidence()`, `probabilities()` by the value as the API wrote it (`"7"`, `"3.5"`) |
| `point(instructions)`, `point(instructions, image, units)` | `PointAnswer` | `x()`, `y()`, `present()`, `confidence()`, `image()`, `width()`, `height()` |
| `box(instructions)`, `box(instructions, image, units)` | `BoxAnswer` | `x1()`, `y1()`, `x2()`, `y2()`, `present()`, `confidence()`, `image()`, `width()`, `height()` |

- `state`, instructions, descriptions and levels take a string or any value Jackson can
  serialise: maps, lists, records, POJOs.
- `choice` takes a map of option name to description, a list of names, or names as
  arguments. Option names are sent and returned exactly as written. Use `options(...)` or a
  `LinkedHashMap` to keep their order.
- `score` levels run from low to high.
- `number` asks for a value on the grid `min`, `min + step`, ... up to `max`. A bound left null
  is left out of the request, so the API's default applies (0, 255 and 1). A grid holds at most
  4,096 values. Pass a `BigDecimal` to send an exact decimal.
- `point` and `box` ask for a place in one of the request's images, below.
- A plain map in the API's wire form is accepted wherever a builder is, so a request copied
  from the API reference runs unchanged.

A point or a box is a place in one of the request's images:

```java
Decision d = dn.decide(DecideRequest.builder()
    .image(Image.fromUrl("belt", "https://files.example.com/line-3/cam-2/0917.jpg"))
    .question("pick", point("Point to the yellow package closest to the robot arm."))
    .question("damage", box("Draw a box around the damaged package.", "belt", null))
    .question("any_damage", truth("Is any package on the belt damaged?"))
    .build());
PointAnswer pick = d.point("pick");     // x 812, y 455, present 0.97
BoxAnswer damage = d.box("damage");     // x1 300, y1 120, x2 520, y2 410
```

`x` and `y` are measured from the top-left corner of the image, `x` to the right and `y` down; a
box runs from its top-left corner (`x1`, `y1`) to its bottom-right corner (`x2`, `y2`). `units`
is `"pixels"` (the default: pixels of the image as sent) or `"normalized"` (fractions of the
width and the height). `present()` is the probability that what the instructions describe is in
the image at all; `confidence()` the probability that the place is right if it is. `image()`,
`width()` and `height()` name the image looked in and its size in pixels. The request needs an
image, and `image` names the one to look in by its id when it carries two or more (null leaves it
out); a reference by number ("Picture 2") goes in the instructions.

Answers are read by question id: `d.truth("id")`, `d.choice("id")`, `d.score("id")`,
`d.number("id")`, `d.point("id")`, `d.box("id")`, or
`d.get("id")` and `d.answers()` for the generic `Answer`. `Answer` is a sealed interface, so a
`switch` or Kotlin `when` over it is exhaustive. An answer of a type newer than this SDK comes
back as `UnknownAnswer` with its fields in `raw()`. So does an answer of a known type whose
fields are missing or of the wrong type; the rest of the decision is read as usual.

Numbers are exactly what the API sent: no rounding and no renormalising.

A `Decision` also carries `model()` (the pinned id of the release that answered), `usage()`,
`requestId()`, `latencyMs()`, `safety()`, `warnings()`, `evidence()` (see Boosters), `fallback()`
(see Fallback) and `raw()`, the parsed body.

`usage().cost()` is what the answer cost, in USD, exactly as the API sent it: `usd()` (the amount
booked for this answer, at your workspace's rates), `modelUsd()` and `boostersUsd()` (its two
parts) and `freeTier()` (true when the free tier covered the request, `modelUsd()` then 0). Read
`usd()` as the total; it is never computed from the parts, since adding doubles can differ from
the booked amount in the last digits. A batch result line and a session's open and frame replies
carry their own. It is null when the answer does not carry a cost.

## The client

| Method | Does |
|---|---|
| `decide(state, questions)`, `decide(state, questions, images, model)`, `decide(state, questions, images, videos, model[, boosters])`, `decide(DecideRequest)` | One decision |
| `decideMany(requests)`, `decideMany(requests, concurrency)` | Many decisions, at most `concurrency` (default 8) at a time. One `DecisionResult` per request, in input order; one failure does not stop the others. Every request is checked first: a `concurrency` below 1 or an invalid request (an empty question map, a value that cannot be serialised) throws `IllegalArgumentException` and nothing is sent, not even the valid requests. |
| `check(state, instructions)`, `check(state, instructions, ifTrue, ifFalse)`, `check(state, instructions, ifTrue, ifFalse, images[, videos], model)` | One `truth` question; returns the `TruthAnswer` (see below for a malformed answer) |
| `classify(state, instructions, options)`, `classify(state, instructions, options, images[, videos], model)` | One `choice` question; returns the `ChoiceAnswer` |
| `score(state, instructions, levels)`, `score(state, instructions, levels, images[, videos], model)` | One `score` question; returns the `ScoreAnswer` |
| `number(state, instructions, min, max, step)`, `number(..., images[, videos], model)` | One `number` question; returns the `NumberAnswer` |
| `gate(action, context)`, `gate(action, context, images[, videos], model)` | Should this action run? See below |
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

In the helpers, as in `decide`, `images` and `videos` may be null and a null or empty `model`
means the client's. Each helper's longest form also takes `boosters` last (see Boosters), for
example `check(state, instructions, ifTrue, ifFalse, images, videos, model, boosters)`.

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
(the image is never decoded), at most 16 images in a request, and ids unique. A 17th image raises
the API's `ValidationError` with a `too_long` entry; any other fault raises the API's own
`BadRequestError`, with no request made. `upload` takes up to 16 images a call. A file is read when the `Image` is made, so a
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

A request body may be at most 25 MiB (`DecisionNode.MAX_BODY_BYTES`, 26,214,400 bytes), counted as
it would be sent: inline images and video frames included, an image to upload named by its upload
id. A larger body raises `RequestTooLargeError` before anything is sent or uploaded, in `decide`,
the helpers, `decideMany` (for the whole call) and each batch line. Images given as a path or bytes
above the inline threshold are uploaded, so they never count toward it; base64 given in a
wire-form map is sent as given and does.

```java
List<String> ids = dn.upload(List.of(Image.fromPath("scan", Path.of("scan.png"))));  // upload now
dn.decide(state, questions, List.of(Image.fromUpload("scan", ids.get(0))), null);
```

Each `PUT` has its own timeout (`uploadTimeout`, 60 s by default), apart from the API attempt's,
since an image of 10 MB on a slow uplink needs longer than an API call. A failed `PUT` is retried
on 5xx, timeouts and connection errors; one that still times out raises `TimeoutError`, and a
storage error it cannot recover from raises `DecisionNodeError` with the storage status,
`errorType()` `upload_failed` and the storage's error code in `reason()`. A `PUT` answered 403 once
its URL has expired is presigned again, once.

### Referring to images and videos

There are two ways to say which image a question is about:

1. **By id (recommended).** Give each image a short, meaningful id and use it in the instructions,
   in backticks: "Does the total on `receipt` match the amount in the claim?"
2. **By number.** Images are numbered from 1 in the order of `images`: "image 2", "the second
   image", "Picture 2" and "the last image" all work.

The model sees each image as `Picture 1 (receipt):`, `Picture 2 (claim_photo):`, so both ways point
to the same image. Prefer ids: they stay right when images are added or reordered. Use names, not
digits, as ids (`front`, not `2`). Videos work the same way, by id or by number ("video 1"),
numbered separately in the order of `videos`. The `image` of a point or box question takes the id.

```java
Decision d = dn.decide(DecideRequest.builder()
    .state("Expense claim 4411: 740.00 EUR.")
    .image(Image.fromUrl("receipt", "https://files.example.com/claims/4411/receipt.jpg"))
    .image(Image.fromUrl("claim_photo", "https://files.example.com/claims/4411/photo.jpg"))
    .question("matches", truth("Does the total on `receipt` match the amount in the claim?"))
    .build());
```

## Videos

A video travels as the frames you already hold, each with its time in seconds, and the server
reads them as one video, in order and with their times. Only frames are sent: no video file, no
video URL and no sound.

```java
List<VideoFrame> frames = new ArrayList<>();
String[] files = {"dock-0000.jpg", "dock-0001.jpg", "dock-0002.jpg"};
for (int i = 0; i < files.length; i++) {
    frames.add(VideoFrame.fromBytes(i * 0.5, Files.readAllBytes(Path.of(files[i]))));  // 0, 0.5 and 1 s
}

Decision d = dn.decide(DecideRequest.builder()
    .state("Warehouse camera 4, loading dock, shift B.")
    .video(Video.of("dock", frames))
    .question("fall", truth("Does a person fall in the video?"))
    .question("forklifts", number("How many forklifts pass the door?", 0, 50))
    .build());
d.truth("fall").truth();
d.warnings();   // the server's notes when it read a video at a lower rate or resolution
```

| Form | Sent as |
|---|---|
| `VideoFrame.fromBytes(t, bytes[, mediaType])` | A frame `t` seconds into the video, its bytes as base64; without a media type it is read from the first bytes (JPEG, PNG or WebP) |
| `VideoFrame.fromBase64(t, text, mediaType)` | A frame given as base64 text, sent as given, with its media type |
| `Video.of(id, frames)`, `.fps(...)`, `.start(...)`, `.end(...)` | A video: its id (unique across the request's images and videos), its frames with strictly increasing times, and optionally the most frames a second to read (above 0 and at most 4; the API's default is 2) and the window `[start, end)` on the frames' own times |

A request takes up to 4 videos, each a `Video` or a map in the wire form, and a frame may be a map
too; both are checked by the same rules. `decide`, the helpers, `decideMany` and batch requests
take videos; sessions do not. Frames always go inline: they are never uploaded or
cached, and no frame is resized or re-encoded. `Decision.usage()` carries `videoFrames()` (the
frames read) and `videoSeconds()` (the seconds of the windows read), both null for a request
without videos, and `Decision.warnings()` the server's notes exactly as sent (empty when there are
none). To turn a video file into frames, use the `dn` command line tool: `dn --video dock.mp4`.

## Boosters

A booster is a specialist model that reads the request's images before the decision. Its reading
is added to what the model sees as evidence and comes back in the response. The model still
answers every question: a booster never answers one, and a request without boosters is answered
exactly as before. Two boosters are available:

| Booster | Reads | Tells the model |
|---|---|---|
| `age` | A picture of a person | How old the person is, with a range and a confidence (the largest face is read) |
| `ai_generated` | Any picture | How likely the picture was made by an image generator, which generator most likely, and whether the file carries Content Credentials (C2PA) and who signed them |

```java
Decision d = dn.decide(DecideRequest.builder()
    .image(Image.fromUpload("selfie", "upl_..."))
    .image(Image.fromUrl("upload", "https://files.example.com/upload.jpg"))
    .boosters(List.of("age", "ai_generated"))                  // sent as "boosters": ["age", "ai_generated"]
    .question("adult", truth("Is the person in `selfie` an adult?"))
    .question("synthetic", truth("Is `upload` AI-generated?"))
    .build());

d.truth("adult").truth();                                // 0.97
Evidence age = d.evidence("age");
age.status();                                            // "ok"
for (Reading r : age.results()) {
    if (r instanceof AgeReading a && a.faces() > 0) {
        System.out.println(a.image() + " " + a.ageYears() + " (" + a.low() + " to " + a.high() + ")");
    }
}
AiGeneratedReading g = (AiGeneratedReading) d.evidence("ai_generated").results().get(1);
g.generated();                                           // 0.93
g.method();                                              // "inference", or "c2pa" from Content Credentials
g.c2paSigner();                                          // who signed them, or null
d.usage().boosterTokens();                               // the readings' share of the input tokens
d.usage().boosters();                                    // booster calls billed
```

A booster that needs a setting is a `Booster` object, sent as
`{"name": ..., "images": [...], "require": true, "options": {...}}` with each setting only when
given (a `Booster` with no setting is sent as its bare name). Here the age booster reads every image, and the AI-generated booster reads only `upload`
and must answer:

```java
DecideRequest.builder()
    .image(Image.fromUpload("selfie", "upl_..."))
    .image(Image.fromUrl("upload", "https://files.example.com/upload.jpg"))
    .booster("age")
    .booster(Booster.of("ai_generated").images("upload").require(true))
    .question("synthetic", truth("Is `upload` AI-generated?"))
    .build();
```

- A booster is named by its name, `booster(name)` or `boosters(List.of(...))`. `Booster.of(name)`
  adds settings: `images(ids...)` (the images it reads), `require(true)` and `options(map)`. A
  booster in its wire form, a map, is accepted too and sent as given. A request takes up to 4
  boosters, each at most once, in the order given. `decide`, the helpers, `decideMany` and batch
  requests take boosters; sessions do not.
- `require(true)`: a booster that fails or does not answer in time fails the request with
  `OverloadedError`, `errorType()` `booster_unavailable`, which is retried like any 529. Without
  it, the decision runs without that reading and its evidence says why.
- `Decision.evidence()` holds one `Evidence` per booster the request named, in its order, and is
  null when the request named none. Each has `version()`, `status()` (`ok`, `timeout`, `error` or
  `skipped`, when none of the request's images is one it reads), `latencyMs()`, `billedCalls()`
  (the calls of that booster billed on this request, null when the API does not say) and
  `results()`, one entry per picture. A picture the booster did not read (it timed out or failed,
  or could not decode the picture) is an `UnknownReading` holding only its `image` (and
  `readable: false`), the status saying why. A reading is an `AgeReading`
  (`image`, `faces`, and `ageYears`, `low`, `high`, `confidence`, each null when `faces` is 0), an
  `AiGeneratedReading` (`image`, `generated`, `confidence`, `method`, `topGenerator` with its
  `name` and `probability`, null unless the image is likely generated, `c2paSigner`, and `c2pa`:
  `untrusted` when the file's Content Credentials validate but their signer is not trusted), or an
  `UnknownReading` with its fields in `raw()` for a booster newer than this SDK. `Reading` is a sealed interface, so a `switch` or Kotlin `when` over
  it is exhaustive.
- `usage().boosterTokens()` is the readings' share of the input tokens, and `usage().boosters()`
  the booster calls billed; both are null for a request without boosters.
- Billing: a booster call is billed for each booster and image read with status `ok`, drawn from
  the prepaid balance with the request's tokens; each booster's price is on
  [the pricing page](https://decisionnode.com/docs/pricing#boosters). A booster that timed out or
  failed is free, and so is a refused request's. The readings' tokens are input tokens at the
  model's price.
- The API checks the list before any booster runs and before any charge: a name that is not a
  booster is a `BadRequestError` (`booster_unknown`), a booster that needs images on a request
  without any, or an image id that is not the request's, too (`booster_input_mismatch`), a booster
  turned off for the workspace a `PermissionDeniedError` (`booster_not_enabled`), and a malformed
  list (more than 4, a name twice, a key in `options`) a `BadRequestError` (`api_usage_error`). Every
  error of a request that named boosters carries `boosters()`: each booster's `status()` and
  `latencyMs()` by name, from the `x-decisionnode-boosters` header, so you can see which required
  booster failed.

## Fine-tuned models

A model your workspace fine-tuned in the console is called by its name in `model`, by the keys of
that workspace: `decisionnode-flash@acme/fraud` runs the version deployed most recently, and
`decisionnode-flash@acme/fraud:3` pins version 3. It answers like its base: the same question
types, answers, limits and errors. The answer's `model()` is always the pinned name, so you can log
which version answered. A name that does not exist, is not deployed or belongs to another
workspace is the API's `BadRequestError` "Unknown model: <name>"; the SDK sends the name as given.
`models()` lists the workspace's fine-tuned models beside the catalogue, each `ModelInfo` with
`fineTuned()` true, `base()` (the base's pinned id), `adapterVersion()` (the version number) and
`status()` (`deployed` answers decisions, `ready` is trained but not deployed); a catalogue entry
has them null.

```java
Decision d = dn.decide(DecideRequest.builder()
    .state(claim)
    .question("fraud", truth("Is this claim fraudulent?"))
    .model("decisionnode-flash@acme/fraud")
    .build());
d.model();   // "decisionnode-flash@acme/fraud:3"
```

## Fallback

When no worker of `decisionnode-moe` can take a request at once, the API answers it from
`decisionnode-flash` instead of making it wait. Such an answer says so: `Decision.fallback()`
holds `servedModel()` (the model that answered) and `requestedModel()` (the model asked for), and
`model()` is the model that answered. On an ordinary answer `fallback()` is null.

To get the full model or nothing, set `fallback(false)`: the request is then answered by the model
asked for, or refused with an `OverloadedError` (a 529), which the client retries like any other.

```java
Decision d = dn.decide(DecideRequest.builder()
    .state(ticket)
    .question("escalate", truth("Escalate to a human now?"))
    .model("decisionnode-moe")
    .fallback(false)
    .build());
```

`fallback` is sent only when you set it. `decide`, the helpers (their longest form takes
`fallback` after `boosters`), `decideMany` and batch requests take it; sessions do not.

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
  the client's own headers. The socket is opened by the client's `HttpClient`, so it uses the
  same proxy and `SSLContext` as every other call. A session refused at connect closes at once: its `SessionClosed` is the
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
`results` and `cancel`, each with an `...Async` form. `results` reads the whole file, however many
lines it holds, line by line as you iterate, so a large file is never held whole; the read fails
with `TimeoutError` when no data arrives for the client's timeout while you wait for the next line,
and a slow reader is never timed out; a line's error is a value (`BatchResult.error()`) of the class
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
`errorType()`, `requestId()`, `reason()`, the server's explanation in words from the
`x-decisionnode-error` header, and `boosters()`, each booster's status and time for a request
that named boosters (empty otherwise).

Only a 2xx response is a success. A 2xx whose body is not JSON is a `ServerError`.

An error whose body is not JSON (an HTML or text page from a proxy, or an empty body) is classed
by its status alone, as in the table below: a 400 is a `BadRequestError` and a 403 a
`PermissionDeniedError`, never their special cases. Its `errorType()` is null, `requestId()` and
`reason()` come from their headers, and the message is the client's own description, for example
`HTTP 413 Content Too Large` or `HTTP 408 Request Timeout (request id req_1)`. The body is never
copied into the error. Retries are the same as for any other body.

| Status | Error | Extra |
|---|---|---|
| 400 | `BadRequestError`; `ContextLimitError` when the input is longer than the model accepts | |
| 401, or no key | `AuthenticationError` | |
| 402 | `InsufficientCreditError` | |
| 403 refusal | `RefusalError` | `category()` (`harm` or `self_harm`), `safety()` |
| 403 other (a booster turned off, a suspended workspace) | `PermissionDeniedError` | |
| 404 | `NotFoundError` | |
| 413, or a body over 25 MiB before sending | `RequestTooLargeError` | |
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

A request the API would refuse for a fixed, documented rule fails before anything is sent, with
what the API itself would answer: the same class and `errorType()`, the documented message, and
for a 422 a `ValidationError` whose `entries()` are the API's list. A local error has no
`status()` and no `requestId()`. The rules are checked in the server's order:

- the 128-level nesting limit (`BadRequestError`, `api_usage_error`, "Invalid request.");
- questions: an unknown type, a missing type or criteria, instructions, a state, a description or
  a level that is not a string, an object or an array, a score without levels, a number grid that
  cannot be built, and a point or box in a request without images, without `image` among two or
  more images, naming no image of the request, or with `units` other than `pixels` and
  `normalized` (`ValidationError`); a choice of no or more than 255 options, a score of more than 10 levels,
  a `truth` with neither instructions nor criteria, a number, point or box question without
  instructions (`BadRequestError` with the API's plain message and no error type);
- more than 16 images or more than 4 videos (`ValidationError` with the API's `too_long` entry on
  `["body", "images"]` or `["body", "videos"]`, after the request's other entries; a local image
  is listed by its id and media type, without its bytes);
- images: an empty id, an id longer than 64 characters (counted as Unicode code points, "Image
  1: its id is longer than 64 characters."; not a session frame's image, which goes without its
  id), a repeated id, bytes that are not JPEG, PNG or WebP or not of
  the type given, over 10 MB, over 40 megapixels, an upload reference of another media type
  (`api_usage_error`); a URL that is not `https` (`api_usage_error`), or over 2,048 characters,
  with user information, on a port other than 443 or on a literal private address
  (`image_url_refused`);
- videos: a video without an id or with an id already used by an image or a video,
  a video without frames, a frame time that is negative or not after the one before it, a frame
  that is not JPEG, PNG or WebP by its media type, `fps` not above 0 and at most 4, `start` or
  `end` negative, and `end` not after `start` (`api_usage_error`); how many frames a request may
  carry, their sizes and their pixels are the server's to judge;
- sessions: `window` outside 1 to 64 and `ttl_seconds` outside 1 to 3,600 (`ValidationError`);
  a frame without a value or an image, with an empty value, an unknown question id, a URL image
  or an image that breaks the rules above: `send` throws `BadRequestError`, writes nothing and
  uses no `seq`;
- batches: a missing, empty, too long or repeated `custom_id`, an `add` with no requests, and
  `list` paging outside its bounds (`api_usage_error`).

Limits that only `GET /v1/models` states (a model without number questions or sessions, a smaller
`max_window`, the batch limits) are checked once `models()` has been called on the client, and
left to the server until then. A request's model finds its entry by name, else by the entry that
states it as its `alias` or pinned `version`; a model the list does not state is left to the
server.

The same checks run on their own, without sending or uploading anything, so a request can be
validated where it is built:

```java
dn.checkRequest(request);                    // as decide would send it
dn.checkRequest(sessionRequest);             // as openSession would
dn.batches().checkCreateRequest(requests);   // as batches().create would; checkAddRequest for add
session.checkFrame(frame);                   // as session.send would; uses no seq
```

Each returns normally when the request would be sent and otherwise throws exactly what sending
would throw. In `decideMany` one faulty request fails the whole call and nothing
is sent. Token limits depend on the model and stay the server's to judge.

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
- A request naming the `ocr` booster (by name or as a `Booster`) takes longer before the model
  answers, so it waits 30 seconds per attempt when you set no timeout. A timeout you set is used
  as given; set at least 30 seconds for such requests. It is never retried after a timeout, because the server finishes the
  timed-out attempt and may bill it: the client raises the `TimeoutError`. Its other retries
  stay. This covers `decide`, the helpers and each `decideMany` request, not sessions, batches or
  `decideRaw`.
- 400, 401, 402, 403, 404 and 422 are never retried.
- Batch `create`, `add` and `finalize` are retried only on 429 and 529, and opening a session is
  never retried on `session_limit_exceeded`; see their sections.
- Batch results are read as they stream: the timeout covers the wait for the response to begin,
  then each wait for more data while you read.
- An image upload's storage `PUT` has its own timeout, `uploadTimeout` (60 s by default).
- Redirects are never followed, so the API key is never sent to another URL or over plain
  HTTP. A 3xx is a `DecisionNodeError` with its status. An `HttpClient` passed to the builder
  must not follow redirects either; see below.

## Configuration

```java
DecisionNode dn = DecisionNode.builder()
    .apiKey("dn_...")                          // default: DECISIONNODE_API_KEY
    .baseUrl("https://api.decisionnode.com")   // default: DECISIONNODE_BASE_URL, else this
    .model("decisionnode-flash")               // default: DECISIONNODE_MODEL, else decisionnode-moe
    .timeout(Duration.ofSeconds(10))           // per attempt, above 0
    .maxRetries(2)                             // 0 or more
    .inlineThreshold(524_288)                  // bytes; local images under it go inline
    .uploadTimeout(Duration.ofSeconds(60))     // per storage PUT of an image upload, above 0
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
| `decisionnode-moe` | The most accurate model, always its current release (default) |
| `decisionnode-flash` | The fast, lower-cost model, always its current release |
| `decisionnode-<family>-<version>` | One release pinned: `models()` lists the pinned ids your key may use |
| `decisionnode-flash@acme/fraud`, `...:3` | Your workspace's fine-tuned models (see Fine-tuned models) |

Every answer's `model()` is the pinned id of the release that answered, so you can log which
release answered a request sent to a family.

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
WebSocket) and batches as well; this SDK passes every case of suite 1.13 in both forms. CI checks the suite out at a pinned commit, set
once as `CONFORMANCE_REF` in `.github/workflows/ci.yml`; every action there is pinned to a
commit SHA and no checkout keeps its credentials.

## License

MIT. Copyright (c) 2026 Bynn Intelligence, Inc.
