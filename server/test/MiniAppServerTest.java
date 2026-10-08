package com.addzero.miniapp;

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
        ProcessBuilder builder = new ProcessBuilder("java", "-cp", "server/out/classes", "com.addzero.miniapp.MiniAppServer");
        builder.environment().put("PORT", String.valueOf(port));
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
            System.out.println("server test passed: " + response.body());
        } finally {
            server.destroy();
        }
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
