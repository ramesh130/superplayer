# Bringing your own HTTP client

How to put the HTTP client your app already has underneath the player, what that client owes in
exchange, what it costs to get each obligation wrong, and how to find out mechanically whether you
got them right.

This is the document for someone who has an HTTP client their team argued about once and does not
want a second one in the same process, and who has never seen this repository. The rules behind it
are [ADR-0004](adr/0004-select-the-http-stack-through-a-superplayer-type.md) and
[ADR-0016](adr/0016-take-the-consumers-transport-through-one-superplayer-interface-and-adapt-it-to-media3.md);
this is what they say in words, to the person they are binding.

---

## Whether you need this at all

**By default, you do not.** A player built with no stack named loads over Media3's
`DefaultHttpDataSource`, which is the platform's `HttpURLConnection`, and everything in this library
— the cache, the retry budgets, the credential repair, the bandwidth estimate, CMCD, the fallback
ladder — already works over it. Naming no stack costs nothing and is what the great majority of apps
should do.

Three reasons are good ones to be here:

- **One connection pool.** Your app already keeps a client with warm connections to the hosts your
  CDN sits behind, and a second pool means a second TLS handshake for the first segment.
- **A protocol the default does not speak.** `HttpURLConnection` is HTTP/1.1 and HTTP/2 at best.
  HTTP/3 and QUIC need a different stack.
- **Your app's own network plumbing** — a corporate proxy configuration, certificate pinning, a
  DNS resolver, a client you are required to route every byte through for auditing.

"Shared auth headers with the app's client" is **not** on that list, and the omission is deliberate:
credentials reach the player through `HeaderProvider` on `superplayer-resilience`, which mints them
per request and repairs a refused one inside the transfer that met the refusal. Bringing your own
client to carry a token means writing that machinery again, worse. See *What is ours, not yours*.

If HTTP/3 is the whole of your reason and you support Android 14 and up, you do not need to write
anything at all: `HttpStack.httpEngine()` is the platform's own engine and is described under
*Choosing a stack* below.

---

## The shape of it

You implement one interface. It moves bytes and it is about nothing else.

```kotlin
public interface HttpTransport {
    @Throws(IOException::class)
    public fun open(request: HttpRequest): HttpResponse
}
```

and four values travel across it:

| Type | What it carries |
| --- | --- |
| `HttpRequest` | `uri`, `method` (`GET`, `POST`, `HEAD`), `headers` (one value per name), `body` (a `ByteArray` for a `POST`, else null), `range` |
| `HttpRange` | `offset`, and `length` or null for "everything from here"; `headerValue()` spells the `Range` header for you |
| `HttpResponse` | `status`, `headers` (many values per name), `body` as an **unread** `InputStream`, and `uri` — where the bytes were really read from |
| `HttpStack` | the selection itself: `HttpStack.of(yourTransport)` |

`open` is called on one of the engine's loader threads, and blocking until the status and headers
have arrived is exactly what is expected of it. The body is then read incrementally by the layer
above; it is never buffered whole, and a 40 MB segment never exists in memory as a byte array.

One transport serves every request a player opens, **concurrently** — manifests, playlists, segments,
licences. Hold no per-request state on it: the per-request state is the `HttpResponse` you return.
Core closes `HttpResponse.body` when it is finished with a response, including when it abandons one
part-read, and you do not close it yourself.

---

## A worked adapter

### What this example is, and what it is not

**The interface names no client and prefers none.** It is written for whatever your app already
ships — OkHttp, Ktor, Cronet, the platform's own, or one this project has never heard of — and
nothing in this library resolves an artifact of any of them.

The adapter below is nonetheless written over **OkHttp**, because naming a real client is the only
way to write an example whose obligations are visible rather than hand-waved: half of the care below
is about what a real client does *on its own initiative*, and no invented client has any. Read it as
the shape an adapter takes, and substitute your own client's spelling for each step.

**It is not compiled by this repository, and nothing in `./gradlew check` runs it.** That is not an
oversight to be fixed later. ADR-0004 rule 2 keeps this repository's dependency graph Media3-only —
no OkHttp, Cronet or Ktor artifact is ever resolved here — which is what keeps Cronet's licence and
Ktor's alpha status out of every consumer's build, and compiling this file would mean taking the
dependency the rule exists to refuse. Rule 2 gained an addendum on 2026-09-18 recording that it binds
the dependency graph and not the prose; naming a client in a document resolves no artifact.

So treat the code below as what it is: prose that has been kept as close to compilable as prose gets,
reviewed against OkHttp's published API, and **not verified by a compiler**. What *is* executable is
the thing that matters more — `HttpTransportConformance`, which runs against your own implementation
and tells you which obligation you broke. Copy the adapter, then run the suite. Do not copy the
adapter and assume it.

Nothing below is derived from any client's internals: it is written against OkHttp's public,
documented API only.

### The client

The two settings that matter are both OkHttp defaults, which is why the adapter does not set them —
but a client your app has already configured may have moved either, so check:

```kotlin
val client = OkHttpClient.Builder()
    // Redirects followed, which is the default. You do not decide whether to follow one — you
    // follow it, as Media3's own stacks do, and report where you ended (obligation 2).
    .followRedirects(true)
    .followSslRedirects(true)
    // No call timeout, which is also the default (0 = none). A whole segment is read through the
    // stream this adapter returns, so a call timeout bounds the *transfer*, and on the slow links
    // this library exists to play well on it fires on healthy playback. `readTimeout` is per socket
    // read and is fine where it is.
    .callTimeout(0, TimeUnit.MILLISECONDS)
    .build()
```

Two things **not** to add to a client used here:

- **No `BrotliInterceptor`, and no interceptor that sets `Accept-Encoding`.** See obligation 3.
- **No interceptor that throws on `!response.isSuccessful`.** See obligation 4. If your app's client
  has one — many do, to make call sites simpler — give the player a client derived from it with that
  interceptor removed (`client.newBuilder()` and a filtered interceptor list), rather than a
  separately configured second client, which would give up the connection pool you came here for.

`retryOnConnectionFailure` is fine left on. It reconnects a broken socket to another route; it does
not re-ask for a resource an origin refused, so it neither spends nor duplicates this library's retry
budgets.

### The transport

```kotlin
import android.net.Uri
import com.superplayer.core.HttpMethod
import com.superplayer.core.HttpRequest
import com.superplayer.core.HttpResponse
import com.superplayer.core.HttpTransport
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OkHttpTransport(private val client: OkHttpClient) : HttpTransport {

    override fun open(request: HttpRequest): HttpResponse {
        val builder = Request.Builder().url(request.uri.toString())

        // Obligation 3: the headers you were given, all of them, and nothing of your own. Core has
        // already put `Accept-Encoding: identity` in here, and copying it through is what stops
        // OkHttp adding `Accept-Encoding: gzip` on its own behalf — it does that only where the
        // caller named no coding.
        request.headers.forEach { (name, value) -> builder.header(name, value) }

        // Obligation 1: the range is a value rather than a header so that the header's spelling is
        // yours. `headerValue()` is that spelling, offered so nobody re-derives the off-by-one
        // (a length of 10 from offset 0 is `bytes=0-9`).
        request.range?.let { builder.header("Range", it.headerValue()) }

        // Null for GET and HEAD, present for the POST a licence acquisition uses. OkHttp requires
        // exactly this pairing and throws on the wrong one, so do not "simplify" it to always-null.
        val body = request.body?.toRequestBody()

        // `execute()` does not throw on a 4xx or a 5xx, which is what obligation 4 needs. It throws
        // an IOException where the request could not be made at all — no route, no name, a
        // handshake that failed — and that is the one thing this method is allowed to throw.
        val response = client.newCall(builder.method(request.method.name, body).build()).execute()

        return HttpResponse(
            // Obligation 4: report the number, do not interpret it. 403 comes back here exactly as
            // 200 does.
            status = response.code,
            // Obligation 4's other half: the headers come back on a refusal as much as on a 200.
            // `toMultimap()` lower-cases the names; core matches case-insensitively either way.
            headers = response.headers.toMultimap(),
            // Unread. Do not call `body.bytes()` or `body.string()`: both read the whole response
            // into memory, which for a segment is the buffer this library works to avoid, and for a
            // live playlist is a transfer that no longer streams.
            body = response.body?.byteStream() ?: ByteArray(0).inputStream(),
            // Obligation 2: where the bytes really came from. OkHttp follows redirects itself, and
            // `response.request.url` is the address of the *last* request in that chain. A response
            // that redirected nowhere reports the address it was asked for, which is what core
            // would have assumed anyway.
            uri = Uri.parse(response.request.url.toString()),
        )
    }
}
```

Two details in that last block are worth their line each.

`response.body` is declared nullable in OkHttp 4 and non-null in OkHttp 5; for a response from
`execute()` it is present in both. The empty stream is there so the expression is total rather than
because it is ever taken, and because an `InputStream` that is empty is something core can close
while a `NullPointerException` is not.

Nothing here closes the response. Core does, through `HttpResponse.body`, and closing that stream is
what cancels the call underneath — which is obligation 5.

### Choosing it

```kotlin
val player = SuperPlayer.Builder(context)
    .setHttpStack(HttpStack.of(OkHttpTransport(app.okHttpClient)))
    .build()
```

---

## The obligations

Five, and **every one of them fails silently**. None throws, none logs, and none shows up as an error
on a device: what each produces is a symptom in a place nobody will connect to an HTTP client. That
is why each is written down with its cost rather than as an instruction, and why the checklist ends
in a suite you run rather than a paragraph you agree with.

Each heading names the check in `HttpTransportConformance` that scores it. Run them; see *Finding out
whether you got it right*.

#### A byte range must be honoured — `verifyByteRangeIsHonoured`

**ADR-0016 rule 5.** A request carrying an `HttpRange` wants part of a resource. Compose it as a
`Range: bytes=…` header — `HttpRange.headerValue()` spells it — and answer with the **206** the
origin sends, `Content-Range` among the response headers, and the bytes of that range and no others.

// spec: RFC 9110 §14.2 — a `Range` request a server honours answers 206 Partial Content.

**What it costs to get wrong.** A DASH initialization segment and a DASH index are fetched this way,
as is any HLS segment carrying an `EXT-X-BYTERANGE`. A transport that drops the range and returns 200
with the whole resource is not a slow transport, it is a broken one: the player reads a resource's
bytes where it expected a fragment's, every read after the first lands at the wrong offset, and what
a viewer sees is content that will not start — with the whole resource paid for on their data plan on
the way there. Core refuses a request it asked to start past byte zero and that came back 200, rather
than reading past it, so this one at least fails loudly. A range from offset zero answered 200 is not
that case and is allowed through, because the bytes then start where the reader expects them.

#### The URI you actually read from must be reported — `verifyRedirectedUriIsReported`

**ADR-0016 rule 6.** Follow redirects — you do not decide whether to, and Media3's own stacks follow
them — then put the address you ended at on `HttpResponse.uri`. Leave it null only where nothing
redirected; core then reads it as the requested URI.

// spec: RFC 9110 §15.4.3 (302 and its `Location`) — a 3xx names a different URI to take the request
to. RFC 3986 §5.1.3 — a relative reference inside a document resolves against the URI the document
was *retrieved* from, which is why the *base* is what matters here.

**What it costs to get wrong.** This is not a diagnostic field. A manifest's references are relative,
and Media3 resolves every one of them — each media playlist, each `BaseURL`, each segment — against
the URI the document was read from. A transport that answers the requested address for a playlist a
CDN redirected sends the player looking for all of it under the **old** address, and what a viewer
sees is content that will not start, on a stream whose every byte was served correctly. What it does
*not* corrupt is your cache keys, which come from the URI the layer above asked for rather than from
this one — worth saying, because "everything downstream is wrong" is the easy claim here and is not
the true one. The damage is the resolution, and the resolution is enough.

#### No content coding of your own — `verifyNoContentCodingIsAdded`

**ADR-0016 rule 7.** Send the headers on the request and no others. In particular, **do not let your
client negotiate compression on its own behalf**. Core has already put `Accept-Encoding: identity` on
every request; your obligation is not to *add* a coding, not to add identity — and a coding the chain
named itself, in whatever case it spelled the header, is the one that goes out.

// spec: RFC 9110 §12.5.3 (`Accept-Encoding`), §8.4 (`Content-Encoding`).

**What it costs to get wrong.** Nothing fails. A client that overrides the header to `gzip` and
transparently decompresses the answer produces a working player — and a bandwidth estimate that is
wrong by the compression ratio, because the bytes core counts are the *decompressed* ones while the
bytes the link moved are the compressed ones. The estimate then describes a link nobody is on, and
the adaptive selector picks renditions against it: a ladder climbed above what the network can carry,
and a rebuffer whose cause appears in no log. It costs the same whether or not the body is media — a
manifest gzips very well — and it is the single most likely misconfiguration on this list, because
asking for gzip is the right default for nearly every other thing an app fetches.

Naming an explicit `Accept-Encoding` is the mechanism, not belt-and-braces: OkHttp and most other
clients add transparent compression only where the caller named no coding, so copying core's header
through is what switches a whole class of client back off. Adding `BrotliInterceptor`, or any
interceptor that sets the header, defeats it.

#### A status is reported, never raised — `verifyStatusIsReportedRatherThanRaised`

**ADR-0016 rule 8.** Answer the number the origin answered — a 404 and a 403 exactly as a 200 — on
`HttpResponse.status`, with the response headers that came with it. Throw an `IOException` only for a
request that could not be made at all: no route, no name, a handshake that failed, a connection that
dropped before a status arrived. A refusal the origin answered is not one of those.

// spec: RFC 9110 §15.5.4 (403), §11.6.1 (`WWW-Authenticate`) — a refusal is a response like any
other, and the challenge that came with it is part of it.

**What it costs to get wrong.** This is the most load-bearing rule of the five, and it is the one
most likely to be broken by a client that is *well* configured for the rest of the app. You report a
number; core turns it into the typed failure the rest of the library reads. A transport that raises
its own exception on a non-2xx instead switches off the credential repair that heals a 401 or 403
inside the transfer that met it, and every classification downstream of it. Nothing fails visibly:
sessions simply end unclassified, a warehouse full of "playback error" replaces a warehouse that knew
which errors were entitlement failures, and the fallback ladder stops being offered the evidence it
climbs on.

The headers matter for the same reason and are dropped more often. They are what a refusal is
diagnosed from afterwards — the `WWW-Authenticate` challenge that says which credential a 401 wanted,
the `Cache-Control` a stale playlist arrived under. A client that hands back a body and drops the
headers costs nobody their playback and everybody the explanation.

#### A cancelled request returns — `verifyCancelledRequestReturns`

**ADR-0016 rule 10.** Core closes `HttpResponse.body` to abandon a load, and on every client this
contract was written against, closing the response is what cancels the call. A read blocked inside
that stream must then return rather than wait for an origin nobody is listening to any more.

**What it costs to get wrong.** Abandoning a transfer part-read is the ordinary case, not the
exceptional one: Media3 does it on a seek, on a track switch and on release. A stream that instead
blocks until the origin finishes holds a loader thread for the length of a transfer nobody wants — a
seek that does not respond until the segment being left behind has finished arriving — and, at the
end, a **stalled release**: a player object and its buffers held by a thread waiting for bytes
belonging to a screen the viewer has already left.

**There is deliberately no timeout around this on our side**, and that is a decision rather than an
omission. A bound would be a duration chosen against no network, no device and no origin, and it
would fire on exactly the slow links this library exists to play well on; enforcing it would cost
every consumer a watchdog thread to insure against a bug in one of them; and it would hide the thing
worth seeing, because cancelled-then-timed-out arrives at the layers above as a load that failed
slowly — a retry budget spent, a ladder climbed, a session classified as a transfer failure, and the
actual cause nowhere in the evidence. Left unbounded it is a hang whose stack trace names your own
`InputStream.read` on the frame below ours, which is the diagnosis. The place this is meant to be
caught is the conformance suite, on your machine, rather than by a timeout on a viewer's device.

---

## What is ours, not yours

The interface is small on purpose, and the most expensive mistake an adopter can make is to assume
that something absent from it is therefore theirs to supply. It is not. Every one of the following
already exists above the transport, and a transport that reimplements one starts **fighting** the
library: two retries where the budget says one, two caches disagreeing about what is on disk, two
credentials in flight for one refusal.

| Not yours | Whose it is |
| --- | --- |
| Retries and backoff | `superplayer-resilience`'s `RetryingLoadErrors`, against `RetryPolicy`'s three budgets — manifest, segment, licence — with equal jitter over `[base/2, base]` |
| Trying another CDN host, another rendition, another source | the six-rung `FallbackLadder`, which climbs in a fixed order and resumes at the position playback had reached |
| Credentials, and refreshing a refused one | `HeaderProvider`, and `TokenRefreshLayer`, which re-asks **inside the transfer that met the 401 or 403** so that the cache, the measurement and CMCD see one transfer |
| Caching | `superplayer-cache`'s `ContentKeyedCache`, keyed by content id and URI path rather than by URL, in a directory you named |
| Downloads and their pins | `superplayer-offline` |
| Counting bytes for the bandwidth estimate | core's adapter, through Media3's `BaseDataSource`, reporting `isNetwork = true` so that the cache-hit exclusion means what it says |
| CMCD (CTA-5004) keys | `CmcdBinding`, attached to the `MediaSource.Factory`, joined to telemetry by a shared session id |
| Deciding what a failure *means* | `superplayer-resilience`'s `ErrorClassifier` — the one place in the library a failure acquires a meaning |
| Turning a status into a typed exception | core's internal adapter, which builds Media3's `InvalidResponseCodeException` carrying the status, the headers and the request that met it |
| Range arithmetic, content length, end-of-stream | core's adapter |
| Manifests, playlists, protocols, DRM | further up still; nothing about HLS, DASH or a licence crosses this interface |

What genuinely *is* yours is the part you came here for: connections, pooling, TLS, the protocol
version, DNS, proxies, and the socket. That is the whole of it, and it is the whole of what ADR-0016
rule 4 means by "it is about bytes".

---

## Finding out whether you got it right

Every obligation above is executable. `HttpTransportConformance` is public API of
`superplayer-testkit` and exists to be run **outside** this repository, against your implementation.

```kotlin
// build.gradle.kts
testImplementation("com.superplayer:superplayer-testkit:<version>")
```

```kotlin
@RunWith(AndroidJUnit4::class)          // Robolectric, or an instrumentation runner
class OkHttpTransportContractTest {

    @Test
    fun `our transport satisfies SuperPlayer's contract`() {
        HttpTransportConformance(OkHttpTransport(app.okHttpClient)).verifyAll()
    }
}
```

`verifyAll()` runs the five in the order the rules number them and stops at the first one broken. Each
is also a method of its own, named in the checklist headings above, for when you are fixing one:
`verifyByteRangeIsHonoured`, `verifyRedirectedUriIsReported`, `verifyNoContentCodingIsAdded`,
`verifyStatusIsReportedRatherThanRaised`, `verifyCancelledRequestReturns`.

**You supply the transport and nothing else.** The origin is the suite's own: a small HTTP/1.1 server
on the loopback interface, started per check and shut down after it, which honours a `Range`,
redirects one address to another, offers gzip to a client that asks for it, refuses one address with a
403 and a `WWW-Authenticate`, and stalls a fourth after a byte. Each check therefore reads **both**
ends of the exchange — what your transport put on the wire as well as what it handed back — which is
why "point it at an origin you control" was rejected: that asks you to build a server that misbehaves
in five specific ways before you can check anything, and then measures your server as much as your
client.

Three practical notes before you run it:

- **It needs an Android runtime**, because `HttpRequest.uri` is an `android.net.Uri`. Robolectric
  (`@RunWith(AndroidJUnit4::class)`) or a device both work; a plain JVM unit test will not.
- **Your transport is handed `http://127.0.0.1:<port>/…` URIs.** A client pinned to one host, or
  refusing plaintext outright, is one to relax for this test — as it would be for any test of it that
  is not a test of your CDN.
- **A failure is an `HttpTransportConformanceException`**, which is an `AssertionError`, so every
  framework renders it as a failed assertion. The suite names no test framework, so JUnit 4, JUnit 5,
  Kotest and an instrumentation runner all run the same checks. Each message names the rule, says what
  your transport did, and says what the rule requires, in that order.

What it cannot tell you: whether TLS, a proxy, HTTP/2 or HTTP/3 behave, since the origin speaks
plaintext HTTP/1.1. And it cannot make you run it — which is the honest limit of the whole mechanism,
and is recorded as such in ADR-0016's *Consequences*.

---

## Choosing a stack

Three, and they are the whole set:

```kotlin
HttpStack.default()          // what ships when nothing is said — Media3's DefaultHttpDataSource
HttpStack.httpEngine()       // the platform's android.net.http.HttpEngine; API 34 and up
HttpStack.of(transport)      // yours
```

`HttpStack.default()` is a *name* for the unstated path rather than a second one: a player built with
it is the same object graph as a player built with no stack at all. It is offered because "which stack
am I on" is a question a bug report has to be able to answer. Java calls it `HttpStack.defaultStack()`,
because `default` is a reserved word there.

`HttpStack.httpEngine()` is the one alternative that costs you no code and this repository no
dependency: Media3's `HttpEngineDataSource` over the platform's own Cronet, updated by the system
rather than by your app, and it buys HTTP/3 and QUIC where your CDN speaks them. It needs **API 34**,
and below that `build()` **refuses** with `HttpStackUnsupportedException` naming the stack and both
levels rather than quietly substituting the default — a silent substitution is what makes a
bandwidth-estimate anomaly impossible to explain six months later. An app that supports older devices
selects it behind its own `Build.VERSION.SDK_INT` check and passes `default()` otherwise. Hold one and
reuse it: each call builds a separate engine, and the platform's engine is a process-sized object with
its own connections and cache.

**Four entry points take a stack, and a stack is not inherited.** Hand the same object to each one
your app opens:

```kotlin
SuperPlayer.Builder(context).setHttpStack(stack)
PlayerPool.Builder(context).setHttpStack(stack)        // every player a feed builds, preload included
Downloads.Builder(context, cache).setHttpStack(stack)  // segments *and* offline licence exchanges
MediaSourceDoctor.Builder(context).setHttpStack(stack)
```

Passing it to three of the four is the defect this rule exists to prevent: a player loading over your
client while its downloads use the platform's is two sets of connection behaviour against one CDN, and
a licence that travelled a different client than the segments it unlocks is a new way to fail an
entitlement. The doctor is on the list for a related reason — its whole promise is to fetch over the
chain a *player* of that request would load through, and a stack it did not share would make that
promise quietly false.

---

## What choosing a stack does not do

Worth stating plainly, because a change at the bottom of a loading chain invites the suspicion that
everything above it moved:

- **It does not change what any metric means.** The telemetry schema version stays **2**; no event
  gains, loses or redefines a field because of the stack underneath. A dashboard built before the
  change reads the same numbers after it. (What a *wrongly implemented* transport changes is the
  numbers themselves — see obligation 3 — which is a different thing and is why the obligation is
  written down with its cost.)
- **It does not change cache keying.** A cached entry is keyed by content id and URI path, taken from
  the request the layer above composed, not from anything the transport reports. The same content
  cached over the default stack is a hit over yours.
- **It does not add, remove or reorder a retry.** The retry budgets, the backoff and its jitter, and
  the six rungs of the fallback ladder are all above the transport and are identical whatever sits
  beneath them.
- **It does not change a profile's numbers.** Buffer durations, the selection ceiling and the pace are
  `PlaybackPolicy`'s and know nothing about the HTTP client.
- **And it does not make you faster on its own.** What you get is your connection pool, your protocol
  and your plumbing. Whether that is a win is a measurement, not a consequence.

---

## See also

- [`adr/0016-take-the-consumers-transport-through-one-superplayer-interface-and-adapt-it-to-media3.md`](adr/0016-take-the-consumers-transport-through-one-superplayer-interface-and-adapt-it-to-media3.md)
  — the record this document explains, rule by rule.
- [`adr/0004-select-the-http-stack-through-a-superplayer-type.md`](adr/0004-select-the-http-stack-through-a-superplayer-type.md)
  — why selection is a SuperPlayer type, why no per-transport module ships, and why this repository
  names no client in its dependency graph.
- [`testing.md`](testing.md) — *The conformance test a consumer runs*, and what a test of a transport
  cannot show.
- [`telemetry-schema.md`](telemetry-schema.md) — the metrics this document promises are unmoved.
