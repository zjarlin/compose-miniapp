package com.addzero.miniapp;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public final class MiniAppServer {
    private final int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "18092"));
    private final Map<String, OrderRecord> orders = new ConcurrentHashMap<>();
    private final WechatPayClient payClient = new WechatPayClient();
    private final List<Store> stores = List.of(
        new Store("store-hotpot", "巷口老火锅", "川味火锅 · 毛肚鲜切", "1.2km", "4.9 月售 3260", "起送 ¥20 · 配送 ¥3 · 约 32 分钟", "/assets/food-hotpot.webp"),
        new Store("store-burger", "大口堡", "汉堡炸鸡 · 可乐套餐", "800m", "4.8 月售 2180", "起送 ¥18 · 配送 ¥2 · 约 25 分钟", "/assets/food-burger.webp"),
        new Store("store-noodles", "兰州牛肉面", "面食 · 牛肉汤", "1.6km", "4.7 月售 1890", "起送 ¥15 · 配送 ¥3 · 约 30 分钟", "/assets/food-noodles.webp"),
        new Store("store-drink", "茶咖研习社", "奶茶咖啡 · 甜点", "2.1km", "4.8 月售 1560", "起送 ¥12 · 配送 ¥4 · 约 28 分钟", "/assets/food-drink.webp")
    );
    private final List<Product> products = List.of(
        new Product("prod-hotpot-1", "招牌牛油锅底", "¥39.00", "月售 1024 · 好评 98%", "/assets/food-hotpot.webp"),
        new Product("prod-hotpot-2", "鲜切毛肚", "¥29.00", "月售 856 · 好评 97%", "/assets/food-noodles.webp"),
        new Product("prod-hotpot-3", "冰粉", "¥9.90", "月售 650 · 好评 99%", "/assets/food-drink.webp")
    );

    public static void main(String[] args) throws IOException {
        MiniAppServer app = new MiniAppServer();
        app.start();
    }

    void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", this::dispatch);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("compose-miniapp server listening on :" + port + ", payConfigured=" + payClient.configured());
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        try {
            cors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if ("/health".equals(path) || "/ready".equals(path)) {
                ok(exchange, Map.of("status", "ok", "payConfigured", payClient.configured()));
            } else if ("/api/stores".equals(path)) {
                ok(exchange, Map.of("items", stores));
            } else if (path.startsWith("/api/stores/") && path.endsWith("/products")) {
                ok(exchange, Map.of("items", products));
            } else if ("/api/orders".equals(path) && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                ok(exchange, Map.of("items", new ArrayList<>(orders.values())));
            } else if ("/api/orders".equals(path) && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                createOrder(exchange);
            } else if (path.startsWith("/api/orders/") && path.endsWith("/pay")) {
                payOrder(exchange, path);
            } else if ("/api/login".equals(path) && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                ok(exchange, Map.of("user", Map.of("nickname", "微信用户", "phone", "13800000000")));
            } else {
                fail(exchange, 404, "NOT_FOUND", "Unknown endpoint: " + path);
            }
        } catch (Exception error) {
            fail(exchange, 500, "INTERNAL_ERROR", error.getMessage());
        }
    }

    private void createOrder(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String storeId = extract(body, "storeId");
        int amountCent = parseItemsAmount(body);
        String id = "order-" + UUID.randomUUID().toString().substring(0, 8);
        String createdAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        OrderRecord order = new OrderRecord(id, storeId, "巷口老火锅外卖", "¥" + String.format("%.2f", amountCent / 100.0), "待支付", createdAt, List.of());
        orders.put(id, order);
        created(exchange, order);
    }

    private void payOrder(HttpExchange exchange, String path) throws Exception {
        String id = path.substring("/api/orders/".length(), path.length() - "/pay".length());
        OrderRecord order = orders.get(id);
        if (order == null) {
            fail(exchange, 404, "ORDER_NOT_FOUND", "Order not found: " + id);
            return;
        }
        int amountCent = amountCent(order.amount());
        Map<String, Object> payment;
        if (payClient.configured()) {
            payment = payClient.createJsapiPayment(order.id(), amountCent, order.title());
        } else {
            payment = new LinkedHashMap<>();
            payment.put("mock", true);
            payment.put("orderId", order.id());
            payment.put("message", "WECHAT_* environment is incomplete; returned a mock payment descriptor.");
        }
        orders.put(id, new OrderRecord(order.id(), order.storeId(), order.title(), order.amount(), "已发起支付", order.createdAt(), order.items()));
        ok(exchange, payment);
    }

    private int parseItemsAmount(String body) {
        int sum = 0;
        int index = 0;
        while ((index = body.indexOf("unitPriceCent", index)) >= 0) {
            int colon = body.indexOf(':', index);
            int end = colon + 1;
            while (end < body.length() && Character.isDigit(body.charAt(end))) {
                end++;
            }
            if (end > colon + 1) {
                sum += Integer.parseInt(body.substring(colon + 1, end));
            }
            index = end;
        }
        return sum <= 0 ? 3800 : sum;
    }

    private int amountCent(String amount) {
        String normalized = amount.replace("¥", "").trim();
        return (int) Math.round(Double.parseDouble(normalized) * 100);
    }

    private String extract(String body, String field) {
        String token = "\"" + field + "\"";
        int index = body.indexOf(token);
        if (index < 0) {
            return "";
        }
        int colon = body.indexOf(':', index + token.length());
        int firstQuote = body.indexOf('"', colon + 1);
        int secondQuote = body.indexOf('"', firstQuote + 1);
        return body.substring(firstQuote + 1, secondQuote);
    }

    private void cors(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private void ok(HttpExchange exchange, Object payload) throws IOException {
        send(exchange, 200, payload);
    }

    private void created(HttpExchange exchange, Object payload) throws IOException {
        send(exchange, 201, payload);
    }

    private void fail(HttpExchange exchange, int status, String code, String message) throws IOException {
        send(exchange, status, Map.of("code", code, "message", message == null ? code : message));
    }

    private void send(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] bytes = MiniJson.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
