package com.addzero.miniapp;

import com.google.gson.JsonParser;
import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

final class MiniAppServerTest {
    private MiniAppServerTest() {
    }

    public static void main(String[] args) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        ProcessBuilder builder = new ProcessBuilder("java", "-cp", "server/out/classes:server/out/lib/*", "com.addzero.miniapp.MiniAppServer");
        builder.environment().put("PORT", String.valueOf(port));
        builder.environment().keySet().removeIf(key -> key.startsWith("WECHAT_"));
        Process server = builder
            .inheritIO()
            .start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            waitReady(client, port);
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (!response.body().contains("\"status\":\"ok\"")) {
                throw new AssertionError("health failed: " + response.body());
            }
            String base = "http://127.0.0.1:" + port;
            HttpResponse<String> stores = client.send(HttpRequest.newBuilder(URI.create(base + "/api/stores")).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (stores.statusCode() != 200 || JsonParser.parseString(stores.body()).getAsJsonObject().getAsJsonArray("items").size() != 4) {
                throw new AssertionError("stores failed: " + stores.body());
            }
            String body = "{\"storeId\": \"store-hotpot\", \"items\": [{\"id\": \"prod-hotpot-1\", \"quantity\": 2, \"unitPriceCent\": 1}]}";
            HttpResponse<String> order = post(client, base + "/api/orders", body);
            var record = JsonParser.parseString(order.body()).getAsJsonObject();
            if (order.statusCode() != 201 || !"¥78.00".equals(record.get("amount").getAsString()) || record.getAsJsonArray("items").size() != 1) {
                throw new AssertionError("server pricing failed: " + order.body());
            }
            HttpResponse<String> payment = post(client, base + "/api/orders/" + record.get("id").getAsString() + "/pay", "{}");
            if (payment.statusCode() != 200 || !JsonParser.parseString(payment.body()).getAsJsonObject().get("mock").getAsBoolean()) {
                throw new AssertionError("mock payment failed: " + payment.body());
            }
            for (String invalid : new String[] {"{}", "{", body.replace("\"quantity\": 2", "\"quantity\": 0"), body.replace("prod-hotpot-1", "unknown")}) {
                if (post(client, base + "/api/orders", invalid).statusCode() != 400) {
                    throw new AssertionError("invalid order accepted: " + invalid);
                }
            }
            if (post(client, base + "/api/wechat/notify", "{}").statusCode() != 503) {
                throw new AssertionError("unconfigured callback must reject notifications");
            }
            System.out.println("server test passed: " + response.body());
        } finally {
            server.destroy();
        }
    }

    private static HttpResponse<String> post(HttpClient client, String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void waitReady(HttpClient client, int port) throws Exception {
        for (int i = 0; i < 30; i++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ready")).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
                Thread.sleep(200);
            }
        }
        throw new IllegalStateException("server did not become ready");
    }
}
