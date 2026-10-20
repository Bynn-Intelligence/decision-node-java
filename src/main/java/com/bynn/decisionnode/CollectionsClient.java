package com.bynn.decisionnode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A workspace's collections, and the public collections it may use: collections of faces (create
 * one, enroll faces into it, list and delete them) and knowledge bases, collections of type
 * {@code text} (add documents, wait until they are ready, search them). Every result is the API's
 * object exactly as sent, but a search's, which is a {@link KnowledgeReading}.
 *
 * <p>{@link #create}, {@link #enroll}, {@link #addDocument}, {@link #replaceDocument} and
 * {@link #updateItem} send an {@code Idempotency-Key}, a new random key for each
 * call and the same on every attempt of it, so the API creates, enrolls and bills once however often
 * the call is tried. They retry as any call, and also on a 409 {@code idempotency_in_progress}
 * after its wait; a 409 {@code idempotency_key_reused} is raised. The reads and deletes carry no
 * key.
 */
public final class CollectionsClient {
    private final DecisionNode client;

    CollectionsClient(DecisionNode client) {
        this.client = client;
    }

    private Transport t() {
        return client.transport();
    }

    /** {@code POST /v1/collections}: a new collection of faces with this name. */
    public Map<String, Object> create(String name) {
        return create(name, "face");
    }

    /** {@code POST /v1/collections} with the collection's type: {@code face} or {@code text} (a knowledge base). */
    public Map<String, Object> create(String name, String type) {
        return create(name, type, null);
    }

    /**
     * {@code POST /v1/collections} with the collection's type and, for a knowledge base, its scope
     * key (1 to 40 of {@code a-z 0-9 _}), sent only when given and fixed for the collection's life:
     * a document's value of that key in its metadata scopes it, and a search or a decision names
     * the value in {@code scope}.
     */
    public Map<String, Object> create(String name, String type, String scopeKey) {
        return Transport.await(createAsync(name, type, scopeKey));
    }

    public CompletableFuture<Map<String, Object>> createAsync(String name) {
        return createAsync(name, "face");
    }

    public CompletableFuture<Map<String, Object>> createAsync(String name, String type) {
        return createAsync(name, type, null);
    }

    public CompletableFuture<Map<String, Object>> createAsync(String name, String type, String scopeKey) {
        byte[] body;
        try {
            LocalChecks.collectionCreate(type, name, scopeKey);
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("type", type);
            b.put("name", name);
            if (scopeKey != null) {
                b.put("scope_key", scopeKey);
            }
            body = Json.write(b);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return t().idempotent("POST", "/v1/collections", body).thenApply(Transport.Response::object);
    }

    /** {@code GET /v1/collections}: the workspace's collections, then the public ones, in the order sent. */
    public List<Map<String, Object>> list() {
        return Transport.await(listAsync());
    }

    @SuppressWarnings("unchecked")
    public CompletableFuture<List<Map<String, Object>>> listAsync() {
        return t().json("GET", "/v1/collections", null, Transport.Policy.DEFAULT).thenApply(r -> {
            List<Object> list = Json.asList(r.object().get("collections"));
            if (list == null) {
                throw Responses.malformedAnswer(r.status(), r.headers(), r.object(), "the response holds no list of collections");
            }
            return (List<Map<String, Object>>) (List<?>) java.util.Collections.unmodifiableList(list);
        });
    }

    /** {@code GET /v1/collections/{id}}; a public collection is named by its id, as given. */
    public Map<String, Object> get(String collectionId) {
        return Transport.await(getAsync(collectionId));
    }

    public CompletableFuture<Map<String, Object>> getAsync(String collectionId) {
        return call("GET", path(collectionId), Transport.Policy.DEFAULT);
    }

    /**
     * {@code DELETE /v1/collections/{id}}. A knowledge base answers {@code {"id", "deleted": true}}
     * once purged within 10 seconds, else {@code {"id", "status": "purging"}}; both are success.
     */
    public Map<String, Object> delete(String collectionId) {
        return Transport.await(deleteAsync(collectionId));
    }

    public CompletableFuture<Map<String, Object>> deleteAsync(String collectionId) {
        return call("DELETE", path(collectionId), Transport.Policy.DEFAULT);
    }

    /**
     * {@code POST /v1/collections/{id}/items}: enrolls the face in one image under the item's
     * {@code reference} (1 to 64 of {@code A-Z a-z 0-9 _ . : -}). The image goes as a decision's
     * image does: a URL as given, local bytes inline or uploaded. A picture the API cannot read is
     * its 422 {@code image_unreadable}.
     */
    public Map<String, Object> enroll(String collectionId, Object image, String reference) {
        return enroll(collectionId, image, reference, null);
    }

    /** {@link #enroll(String, Object, String)} with a {@code label} of at most 128 characters, sent only when given. */
    public Map<String, Object> enroll(String collectionId, Object image, String reference, String label) {
        return Transport.await(enrollAsync(collectionId, image, reference, label));
    }

    public CompletableFuture<Map<String, Object>> enrollAsync(String collectionId, Object image, String reference) {
        return enrollAsync(collectionId, image, reference, null);
    }

    public CompletableFuture<Map<String, Object>> enrollAsync(String collectionId, Object image, String reference, String label) {
        return enrollAsync(collectionId, image, reference, label, null);
    }

    /**
     * {@link #enroll(String, Object, String, String)} with the item's {@code metadata}, sent only
     * when given: at most 20 keys of 1 to 40 of {@code a-z 0-9 _}, each a text of at most 500
     * characters, a number or a boolean.
     */
    public Map<String, Object> enroll(String collectionId, Object image, String reference, String label, Map<String, ?> metadata) {
        return Transport.await(enrollAsync(collectionId, image, reference, label, metadata));
    }

    public CompletableFuture<Map<String, Object>> enrollAsync(String collectionId, Object image, String reference, String label,
            Map<String, ?> metadata) {
        String path;
        try {
            path = path(collectionId) + "/items";
            LocalChecks.collectionEnroll(image, reference, label, metadata);
            // Only a URL's rules are a decision's here; every other fault of the picture is the
            // server's to find (its 422 image_unreadable).
            if (image instanceof Image img && img.url() != null) {
                LocalChecks.url(img.id(), img.url());
            }
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return client.uploads().wires(List.of(image), true, false).thenCompose(w -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("image", w.wires().get(0));
            body.put("reference", reference);
            if (label != null) {
                body.put("label", label);
            }
            if (metadata != null) {
                body.put("metadata", metadata);
            }
            return t().idempotent("POST", path, Json.write(body));
        }).thenApply(Transport.Response::object);
    }

    /**
     * {@code PATCH /v1/collections/{id}/items/{item_id}}: changes the fields the update sets, and
     * returns the item as sent. A text item's fields apply at once, never embedded again or billed,
     * but a new title, which makes a new version (the API's 202). It carries an {@code Idempotency-Key} and retries as
     * {@link #enroll} does.
     */
    public Map<String, Object> updateItem(String collectionId, String itemId, ItemUpdate update) {
        return Transport.await(updateItemAsync(collectionId, itemId, update));
    }

    public CompletableFuture<Map<String, Object>> updateItemAsync(String collectionId, String itemId, ItemUpdate update) {
        String path;
        byte[] body;
        try {
            Map<String, Object> f = update == null ? Map.of() : update.fields();
            LocalChecks.collectionUpdate(f);
            path = path(collectionId) + "/items/" + segment(itemId, "itemId");
            body = Json.write(f);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return t().idempotent("PATCH", path, body).thenApply(Transport.Response::object);
    }

    /** {@code GET /v1/collections/{id}/items}: the first page with the server's default size. */
    public Map<String, Object> listItems(String collectionId) {
        return listItems(collectionId, null, null);
    }

    /** {@code GET /v1/collections/{id}/items?offset=&limit=}. Null leaves a parameter out. */
    public Map<String, Object> listItems(String collectionId, Integer offset, Integer limit) {
        return Transport.await(listItemsAsync(collectionId, offset, limit));
    }

    public CompletableFuture<Map<String, Object>> listItemsAsync(String collectionId, Integer offset, Integer limit) {
        return listItemsAsync(collectionId, offset, limit, null, null);
    }

    /**
     * {@code GET /v1/collections/{id}/items} with an exact {@code reference} or a
     * {@code referencePrefix} (one of them, each 1 to 64 of {@code A-Z a-z 0-9 _ . : -}); null leaves
     * a parameter out. The page, as sent, says whether its total was capped.
     */
    public Map<String, Object> listItems(String collectionId, Integer offset, Integer limit, String reference,
            String referencePrefix) {
        return Transport.await(listItemsAsync(collectionId, offset, limit, reference, referencePrefix));
    }

    public CompletableFuture<Map<String, Object>> listItemsAsync(String collectionId, Integer offset, Integer limit, String reference,
            String referencePrefix) {
        return listItemsAsync(collectionId, offset, limit, reference, referencePrefix, null, null);
    }

    /**
     * {@code GET /v1/collections/{id}/items} on a knowledge base, with {@code q} (1 to 200
     * characters, matched without regard to case against a document's title, file name or
     * reference) and {@code sort} ({@code newest}, {@code oldest} or {@code title}), each sent only
     * when given; null leaves a parameter out. The page, as sent, echoes both.
     */
    public Map<String, Object> listItems(String collectionId, Integer offset, Integer limit, String reference, String referencePrefix,
            String q, String sort) {
        return Transport.await(listItemsAsync(collectionId, offset, limit, reference, referencePrefix, q, sort));
    }

    public CompletableFuture<Map<String, Object>> listItemsAsync(String collectionId, Integer offset, Integer limit, String reference,
            String referencePrefix, String q, String sort) {
        StringBuilder query = new StringBuilder();
        String path;
        try {
            LocalChecks.listParams(offset, limit);
            if (reference != null && referencePrefix != null) {
                throw LocalChecks.usage("Send query parameter reference or reference_prefix, not both.", null);
            }
            if (reference != null) {
                LocalChecks.referenceQuery("reference", reference);
            }
            if (referencePrefix != null) {
                LocalChecks.referenceQuery("reference_prefix", referencePrefix);
            }
            if (q != null && (q.isEmpty() || q.codePointCount(0, q.length()) > ITEMS_Q_MAX)) {
                throw LocalChecks.usage("Query parameter q must be 1 to 200 characters.", null);
            }
            if (sort != null && !ITEMS_SORTS.contains(sort)) {
                throw LocalChecks.usage("Query parameter sort must be newest, oldest or title, got " + LocalChecks.printable(sort, 16) + ".",
                        null);
            }
            path = path(collectionId) + "/items";
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (offset != null) {
            query.append("offset=").append(offset);
        }
        if (limit != null) {
            query.append(query.length() > 0 ? "&" : "").append("limit=").append(limit);
        }
        String[][] named = {{"reference", reference}, {"reference_prefix", referencePrefix}, {"q", q}, {"sort", sort}};
        for (String[] p : named) {
            if (p[1] != null) {
                query.append(query.length() > 0 ? "&" : "").append(p[0]).append('=').append(segment(p[1], p[0]));
            }
        }
        return call("GET", path + (query.length() > 0 ? "?" + query : ""), Transport.Policy.DEFAULT);
    }

    /** The longest {@code q} of a knowledge base's items list, in characters. */
    static final int ITEMS_Q_MAX = 200;
    /** The orders of a knowledge base's items list. */
    static final List<String> ITEMS_SORTS = List.of("newest", "oldest", "title");

    /** {@code DELETE /v1/collections/{id}/items/{item_id}}. */
    public Map<String, Object> deleteItem(String collectionId, String itemId) {
        return Transport.await(deleteItemAsync(collectionId, itemId));
    }

    public CompletableFuture<Map<String, Object>> deleteItemAsync(String collectionId, String itemId) {
        String path;
        try {
            path = path(collectionId) + "/items/" + segment(itemId, "itemId");
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return call("DELETE", path, Transport.Policy.DEFAULT);
    }

    // ------------------------------------------------------------------ knowledge bases

    /** The default of {@link #waitUntilReady}'s timeout. */
    public static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(300);
    /** The default of {@link #waitUntilReady}'s first wait between reads; each next one is 1.5 times longer, up to 10 seconds. */
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);
    private static final long MAX_POLL_MS = 10_000;
    private static final long MIN_POLL_MS = 100;

    /**
     * {@code POST /v1/collections/{id}/items}: adds a document to a knowledge base. Its text is
     * added asynchronously: the item comes back {@code queued}, then is {@code processing} and
     * {@code ready} or {@code failed}; {@link #waitUntilReady(String, String)} waits for it.
     */
    public Map<String, Object> addDocument(String collectionId, Document document) {
        return addDocument(collectionId, document, DocumentOptions.NONE);
    }

    /**
     * {@link #addDocument(String, Document)} with the document's title, reference, pin, label,
     * metadata, and {@code waitUntilReady}, which asks the server to answer once the document is
     * ready (within its 10 seconds, else as it stands).
     *
     * <p>Text in memory, and a text or Markdown file under the client's inline threshold that is
     * UTF-8, is sent inline in this one call; any other file is uploaded first and named by its
     * upload id. The call carries an {@code Idempotency-Key}, the same on every attempt.
     */
    public Map<String, Object> addDocument(String collectionId, Document document, DocumentOptions options) {
        return Transport.await(addDocumentAsync(collectionId, document, options));
    }

    public CompletableFuture<Map<String, Object>> addDocumentAsync(String collectionId, Document document) {
        return addDocumentAsync(collectionId, document, DocumentOptions.NONE);
    }

    public CompletableFuture<Map<String, Object>> addDocumentAsync(String collectionId, Document document, DocumentOptions options) {
        String path;
        try {
            path = path(collectionId) + "/items";
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return writeDocument("POST", path, document, options);
    }

    /**
     * {@code PUT /v1/collections/{id}/items/{item_id}}: replaces a document whole, its text and
     * its fields, as a new version; the same text again is a no-op (the API's 200). The version
     * serving goes on serving until the new one is ready.
     */
    public Map<String, Object> replaceDocument(String collectionId, String itemId, Document document) {
        return replaceDocument(collectionId, itemId, document, DocumentOptions.NONE);
    }

    /** {@link #replaceDocument(String, String, Document)} with the document's fields; see {@link #addDocument(String, Document, DocumentOptions)}. */
    public Map<String, Object> replaceDocument(String collectionId, String itemId, Document document, DocumentOptions options) {
        return Transport.await(replaceDocumentAsync(collectionId, itemId, document, options));
    }

    public CompletableFuture<Map<String, Object>> replaceDocumentAsync(String collectionId, String itemId, Document document) {
        return replaceDocumentAsync(collectionId, itemId, document, DocumentOptions.NONE);
    }

    public CompletableFuture<Map<String, Object>> replaceDocumentAsync(String collectionId, String itemId, Document document,
            DocumentOptions options) {
        String path;
        try {
            path = path(collectionId) + "/items/" + segment(itemId, "itemId");
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return writeDocument("PUT", path, document, options);
    }

    /**
     * A document's add or replace: the local checks, then the body inline, by the caller's upload
     * id, or by an upload this call places. An upload this call placed that the API calls stale is
     * uploaded again and the call sent once more under a new key, not counted as a retry.
     */
    private CompletableFuture<Map<String, Object>> writeDocument(String method, String path, Document document, DocumentOptions options) {
        DocumentOptions o = options == null ? DocumentOptions.NONE : options;
        String target = o.waitUntilReady() ? path + "?wait=true" : path;
        Duration limit = o.waitUntilReady() ? t().waitTimeout() : t().timeout;
        Map<String, Object> body;
        Document.File file = null;
        try {
            Objects.requireNonNull(document, "document");
            // The document's own faults come first, then the fields'; a file's type or size is its
            // own 400, before either.
            List<Map<String, Object>> fieldEntries = LocalChecks.textFieldEntries(o.fields());
            List<Map<String, Object>> entries;
            if (document.isText()) {
                entries = LocalChecks.documentEntries(true, o.title(), document.text(), document.format());
                body = inline(o.title(), document.text(), document.format(), o.fields());
            } else if (document.isUpload()) {
                entries = LocalChecks.documentEntries(false, o.title(), null, null);
                body = byUpload(document.uploadId(), o.title(), o.fields());
            } else {
                file = document.file(0);
                String text = file.inlineText(client.inlineThreshold());
                if (text != null) {
                    // A small text or Markdown file goes inline: one call instead of three.
                    String title = o.title() != null ? o.title() : file.stem();
                    entries = LocalChecks.documentEntries(true, title, null, null);
                    body = inline(title, text, "text/markdown".equals(file.contentType()) ? "markdown" : "text", o.fields());
                    file = null;
                } else {
                    entries = LocalChecks.documentEntries(false, o.title(), null, null);
                    body = null;
                }
            }
            entries.addAll(fieldEntries);
            if (!entries.isEmpty()) {
                throw LocalChecks.invalidEntries(entries);
            }
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (file == null) {
            return t().idempotent(method, target, Json.write(body), UUID.randomUUID().toString(), limit)
                    .thenApply(Transport.Response::object);
        }
        Document.File upload = file;
        return uploadAndSend(method, target, upload, o, limit).exceptionallyCompose(err -> {
            Throwable cause = Transport.unwrap(err);
            if (cause instanceof BadRequestError bad && DecisionNode.STALE_UPLOAD.contains(bad.errorType())) {
                return uploadAndSend(method, target, upload, o, limit);
            }
            return CompletableFuture.failedFuture(cause);
        });
    }

    /** Uploads the file with the knowledge purpose, then sends the item call naming it, under a new key. */
    private CompletableFuture<Map<String, Object>> uploadAndSend(String method, String target, Document.File file, DocumentOptions o,
            Duration limit) {
        return client.uploads().uploadDocuments(List.of(file), client.documentUploadTimeout()).thenCompose(ids -> {
            byte[] body = Json.write(byUpload(ids.get(0), o.title(), o.fields()));
            return t().idempotent(method, target, body, UUID.randomUUID().toString(), limit);
        }).thenApply(Transport.Response::object);
    }

    private static Map<String, Object> inline(String title, String text, String format, Map<String, Object> fields) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("title", title);
        b.put("text", text);
        if (format != null) {
            b.put("format", format);
        }
        b.putAll(fields);
        return b;
    }

    private static Map<String, Object> byUpload(String uploadId, String title, Map<String, Object> fields) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("upload_id", uploadId);
        if (title != null) {
            b.put("title", title);
        }
        b.putAll(fields);
        return b;
    }

    /** {@code GET /v1/collections/{id}/items/{item_id}}: one item, as sent. */
    public Map<String, Object> getItem(String collectionId, String itemId) {
        return getItem(collectionId, itemId, false);
    }

    /**
     * {@code GET /v1/collections/{id}/items/{item_id}}, with {@code ?include=text} when
     * {@code includeText} is true: a document's stored text in {@code text} (null while nothing
     * serves). The byte offsets of chunks and passages are UTF-8 offsets into that text: encode it
     * to UTF-8 before slicing it by them.
     */
    public Map<String, Object> getItem(String collectionId, String itemId, boolean includeText) {
        return Transport.await(getItemAsync(collectionId, itemId, includeText));
    }

    public CompletableFuture<Map<String, Object>> getItemAsync(String collectionId, String itemId) {
        return getItemAsync(collectionId, itemId, false);
    }

    public CompletableFuture<Map<String, Object>> getItemAsync(String collectionId, String itemId, boolean includeText) {
        String path;
        try {
            path = path(collectionId) + "/items/" + segment(itemId, "itemId");
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return call("GET", includeText ? path + "?include=text" : path, Transport.Policy.DEFAULT);
    }

    /** {@code GET /v1/collections/{id}/items/{item_id}/chunks}: the first page with the server's default size. */
    public Map<String, Object> listChunks(String collectionId, String itemId) {
        return listChunks(collectionId, itemId, null, null);
    }

    /**
     * {@code GET /v1/collections/{id}/items/{item_id}/chunks?offset=&limit=}: a document's chunks
     * ({@code chunk}, {@code heading}, {@code byte_start}, {@code byte_end}, {@code page_start},
     * {@code page_end}, {@code tokens}, {@code text}) with {@code offset}, {@code limit},
     * {@code total} and {@code next_offset}, as sent. Null leaves a parameter out.
     */
    public Map<String, Object> listChunks(String collectionId, String itemId, Integer offset, Integer limit) {
        return Transport.await(listChunksAsync(collectionId, itemId, offset, limit));
    }

    public CompletableFuture<Map<String, Object>> listChunksAsync(String collectionId, String itemId) {
        return listChunksAsync(collectionId, itemId, null, null);
    }

    public CompletableFuture<Map<String, Object>> listChunksAsync(String collectionId, String itemId, Integer offset, Integer limit) {
        String path;
        try {
            LocalChecks.listParams(offset, limit);
            path = path(collectionId) + "/items/" + segment(itemId, "itemId") + "/chunks";
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        StringBuilder q = new StringBuilder();
        if (offset != null) {
            q.append("offset=").append(offset);
        }
        if (limit != null) {
            q.append(q.length() > 0 ? "&" : "").append("limit=").append(limit);
        }
        return call("GET", path + (q.length() > 0 ? "?" + q : ""), Transport.Policy.DEFAULT);
    }

    /**
     * Waits until no document of the knowledge base is {@code queued} or {@code processing}, and
     * returns the collection. See {@link #waitUntilReady(String, String, Duration, Duration)}.
     */
    public Map<String, Object> waitUntilReady(String collectionId) {
        return waitUntilReady(collectionId, null, null, null);
    }

    /**
     * Waits until the document is {@code ready} or {@code failed}, and returns the item, whichever
     * it is: a failed document is a value, its {@code error} saying why. See
     * {@link #waitUntilReady(String, String, Duration, Duration)}.
     */
    public Map<String, Object> waitUntilReady(String collectionId, String itemId) {
        return waitUntilReady(collectionId, itemId, null, null);
    }

    /**
     * Reads the document ({@code itemId} given) until its {@code status} is {@code ready} or
     * {@code failed}, or the collection ({@code itemId} null) until its {@code status_counts} show
     * nothing {@code queued} or {@code processing}, and returns what it read last. The first read
     * is made at once; then it waits {@code pollInterval} (default 1 second), 1.5 times longer
     * after each read up to 10 seconds. Each read is retried as any call.
     *
     * @param timeout      how long to wait in all, null for {@link #DEFAULT_WAIT_TIMEOUT}
     * @param pollInterval the first wait between reads, at least 100 ms; null for
     *                     {@link #DEFAULT_POLL_INTERVAL}
     * @throws TimeoutError when it is not ready within {@code timeout}
     * @throws IllegalArgumentException for a timeout not above 0 or a poll interval under 100 ms,
     *     before anything is sent
     */
    public Map<String, Object> waitUntilReady(String collectionId, String itemId, Duration timeout, Duration pollInterval) {
        return Transport.await(waitUntilReadyAsync(collectionId, itemId, timeout, pollInterval));
    }

    public CompletableFuture<Map<String, Object>> waitUntilReadyAsync(String collectionId) {
        return waitUntilReadyAsync(collectionId, null, null, null);
    }

    public CompletableFuture<Map<String, Object>> waitUntilReadyAsync(String collectionId, String itemId) {
        return waitUntilReadyAsync(collectionId, itemId, null, null);
    }

    public CompletableFuture<Map<String, Object>> waitUntilReadyAsync(String collectionId, String itemId, Duration timeout,
            Duration pollInterval) {
        String path;
        long budget;
        long first;
        try {
            Duration total = timeout == null ? DEFAULT_WAIT_TIMEOUT : timeout;
            Duration interval = pollInterval == null ? DEFAULT_POLL_INTERVAL : pollInterval;
            if (total.isNegative() || total.isZero()) {
                throw new IllegalArgumentException("timeout must be above 0, got " + total);
            }
            if (interval.toMillis() < MIN_POLL_MS) {
                throw new IllegalArgumentException("pollInterval must be at least 0.1 seconds, got " + interval);
            }
            path = itemId == null ? path(collectionId) : path(collectionId) + "/items/" + segment(itemId, "itemId");
            budget = total.toMillis();
            first = interval.toMillis();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return poll(path, itemId != null, budget, System.nanoTime() + budget * 1_000_000L, first, MAX_POLL_MS);
    }

    /**
     * One read, then the next after its wait, until the deadline: a wall-clock time from the call,
     * in {@link System#nanoTime()}. The last wait is cut to the deadline, so a final read is made
     * at it.
     */
    private CompletableFuture<Map<String, Object>> poll(String path, boolean item, long budget, long deadline, double interval,
            long most) {
        return call("GET", path, Transport.Policy.DEFAULT).thenCompose(obj -> {
            if (item ? settled(obj) : idle(obj)) {
                return CompletableFuture.completedFuture(obj);
            }
            long left = (deadline - System.nanoTime()) / 1_000_000;
            if (left <= 0) {
                return CompletableFuture.failedFuture(new TimeoutError(
                        (item ? "the document" : "the knowledge base") + " is not ready after " + budget / 1000.0 + " seconds", null));
            }
            long wait = Math.min(Math.round(interval), left);
            return t().sleep(wait).thenCompose(v -> poll(path, item, budget, deadline, Math.min(interval * 1.5, most), most));
        });
    }

    /** An item that is {@code ready} or {@code failed}; any other status, one this SDK does not know included, is waited on. */
    private static boolean settled(Map<String, Object> item) {
        Object status = item.get("status");
        return "ready".equals(status) || "failed".equals(status);
    }

    /** A collection with nothing {@code queued} or {@code processing} (each 0 or absent). */
    private static boolean idle(Map<String, Object> collection) {
        Map<String, Object> counts = Json.asMap(collection.get("status_counts"));
        if (counts == null) {
            return true;
        }
        return zero(counts.get("queued")) && zero(counts.get("processing"));
    }

    private static boolean zero(Object n) {
        return n == null || (n instanceof Number x && x.doubleValue() == 0);
    }

    /** {@code POST /v1/collections/{id}/search} with the query alone. */
    public KnowledgeReading search(String collectionId, String query) {
        return search(collectionId, query, null);
    }

    /**
     * {@code POST /v1/collections/{id}/search}: the passages a decision naming the knowledge base
     * would be given for this query, each with its text, to try a query without a decision. Billed
     * as one knowledge call; needs a full-scope key. The options are sent as given and checked by
     * the API.
     */
    public KnowledgeReading search(String collectionId, String query, SearchOptions options) {
        return Transport.await(searchAsync(collectionId, query, options));
    }

    public CompletableFuture<KnowledgeReading> searchAsync(String collectionId, String query) {
        return searchAsync(collectionId, query, null);
    }

    public CompletableFuture<KnowledgeReading> searchAsync(String collectionId, String query, SearchOptions options) {
        String path;
        byte[] body;
        try {
            path = path(collectionId) + "/search";
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("query", query);
            if (options != null) {
                b.putAll(options.options());
            }
            body = Json.write(b);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return t().json("POST", path, body, Transport.Policy.DEFAULT).thenApply(r -> {
            KnowledgeReading reading = Responses.knowledgeReading(java.util.Collections.unmodifiableMap(r.object()));
            if (reading == null) {
                throw Responses.malformedAnswer(r.status(), r.headers(), r.object(), "the search answer is not a knowledge reading");
            }
            return reading;
        });
    }

    /**
     * {@code DELETE /v1/collections/{id}/items?status=failed}: deletes every failed document of a
     * knowledge base at once and returns the API's answer as sent, {@code {"deleted": n}}. Running
     * it again deletes nothing more, so it retries as any call.
     */
    public Map<String, Object> deleteFailed(String collectionId) {
        return Transport.await(deleteFailedAsync(collectionId));
    }

    public CompletableFuture<Map<String, Object>> deleteFailedAsync(String collectionId) {
        String path;
        try {
            path = path(collectionId) + "/items?status=failed";
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return call("DELETE", path, Transport.Policy.DEFAULT);
    }

    private CompletableFuture<Map<String, Object>> call(String method, String path, Transport.Policy policy) {
        return t().json(method, path, null, policy).thenApply(Transport.Response::object);
    }

    private static String path(String collectionId) {
        return "/v1/collections/" + segment(collectionId, "collectionId");
    }

    /** One path segment, percent-encoded but for letters, digits and {@code _ . - ~}. */
    static String segment(String value, String what) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(what + " must not be empty");
        }
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-'
                    || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }
}
