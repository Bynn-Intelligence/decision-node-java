package com.bynn.decisionnode;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * A workspace's collections of faces, and the public collections it may use: create one, enroll
 * faces into it, list and delete them. Every result is the API's object exactly as sent.
 *
 * <p>{@link #create} and {@link #enroll} retry only on 429 and 529, which the API answers before
 * applying anything: a repeated enrollment would enroll the face twice and bill it twice. The
 * reads and deletes retry as any call.
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

    /** {@code POST /v1/collections} with the collection's type ({@code face}, the only one). */
    public Map<String, Object> create(String name, String type) {
        return Transport.await(createAsync(name, type));
    }

    public CompletableFuture<Map<String, Object>> createAsync(String name) {
        return createAsync(name, "face");
    }

    public CompletableFuture<Map<String, Object>> createAsync(String name, String type) {
        byte[] body;
        try {
            LocalChecks.collectionCreate(type, name);
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("type", type);
            b.put("name", name);
            body = Json.write(b);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return t().json("POST", "/v1/collections", body, Transport.Policy.MUTATION).thenApply(Transport.Response::object);
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

    /** {@code DELETE /v1/collections/{id}}. */
    public Map<String, Object> delete(String collectionId) {
        return Transport.await(deleteAsync(collectionId));
    }

    public CompletableFuture<Map<String, Object>> deleteAsync(String collectionId) {
        return call("DELETE", path(collectionId), Transport.Policy.DEFAULT);
    }

    /**
     * {@code POST /v1/collections/{id}/items}: enrolls the face in one image under the item's
     * {@code reference} (1 to 64 of {@code A-Z a-z 0-9 _ . : -}). The image goes as a decision's
     * image does: a URL as given, local bytes inline or uploaded.
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
        String path;
        try {
            path = path(collectionId) + "/items";
            LocalChecks.collectionEnroll(image, reference, label);
            LocalChecks.images(List.of(image), client.limits(null));
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
            return t().json("POST", path, Json.write(body), Transport.Policy.MUTATION);
        }).thenApply(Transport.Response::object);
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
        StringBuilder q = new StringBuilder();
        String path;
        try {
            LocalChecks.listParams(offset, limit);
            path = path(collectionId) + "/items";
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (offset != null) {
            q.append("offset=").append(offset);
        }
        if (limit != null) {
            q.append(q.length() > 0 ? "&" : "").append("limit=").append(limit);
        }
        return call("GET", path + (q.length() > 0 ? "?" + q : ""), Transport.Policy.DEFAULT);
    }

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
