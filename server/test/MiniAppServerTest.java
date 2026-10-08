package com.addzero.miniapp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

final class MiniAppServerTest {
    private MiniAppServerTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("PORT", "18093");
        ProcessBuilder builder = new ProcessBuilder("java", "-cp", "server/out/classes", "com.addzero.miniapp.MiniAppServer");
        builder.environment().put("PORT", "18093");
        Process server = builder
            .inheritIO()
            .start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            waitReady(client);
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:18093/health")).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (!response.body().contains("\"status\":\"ok\"")) {
                throw new AssertionError("health failed: " + response.body());
            }
            System.out.println("server test passed: " + response.body());
        } finally {
            server.destroy();
        }
    }

    private static void waitReady(HttpClient client) throws Exception {
        for (int i = 0; i < 30; i++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:18093/ready")).GET().build();
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
