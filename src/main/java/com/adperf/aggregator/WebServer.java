package com.adperf.aggregator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Minimal upload page using the JDK's built-in HTTP server (no framework).
 *
 * <p>{@code POST /analyze} takes the raw file as the request body and streams it straight into the
 * {@link Aggregator}, so the upload is never stored in memory or on disk. The response is JSON containing a
 * summary and the two result CSVs as text; the page renders and downloads them.
 */
final class WebServer {

    private WebServer() {
    }

    static HttpServer start(int port) throws IOException {
        byte[] page = loadPage();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", exchange -> handle(exchange, page));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    private static void handle(HttpExchange exchange, byte[] page) throws IOException {
        try (exchange) {
            String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            switch (route) {
                case "GET /" -> send(exchange, 200, "text/html; charset=utf-8", page);
                case "POST /analyze" -> analyze(exchange);
                default -> send(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static void analyze(HttpExchange exchange) throws IOException {
        int status;
        String json;
        try (InputStream body = exchange.getRequestBody()) {
            try {
                json = toJson(Aggregator.analyze(body));
                status = 200;
            } catch (InvalidInputException e) {
                body.transferTo(OutputStream.nullOutputStream()); // let the browser finish sending before we reply
                json = "{\"error\":" + quote(e.getMessage()) + "}";
                status = 400;
            }
        }
        send(exchange, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    static String toJson(Report report) {
        List<String> skipped = new ArrayList<>();
        report.skippedByReason().forEach((reason, count) -> skipped.add("%s: %,d rows".formatted(reason.description, count)));
        report.skippedSamples().forEach(row ->
                skipped.add("line %d (%s): %s".formatted(row.lineNumber(), row.reason().description, row.content())));

        return "{"
                + "\"dataRows\":" + report.dataRows()
                + ",\"validRows\":" + report.validRows()
                + ",\"skippedRows\":" + report.skippedRows()
                + ",\"campaigns\":" + report.campaignCount()
                + ",\"elapsedMillis\":" + report.elapsed().toMillis()
                + ",\"skipped\":[" + String.join(",", skipped.stream().map(WebServer::quote).toList()) + "]"
                + ",\"topCtrCsv\":" + quote(ResultCsv.format(report.topByCtr()))
                + ",\"topCpaCsv\":" + quote(ResultCsv.format(report.topByCpa()))
                + "}";
    }

    /** Encodes a string as a JSON string literal. */
    static String quote(String text) {
        StringBuilder json = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                default -> {
                    if (c < 0x20) {
                        json.append("\\u%04x".formatted((int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        return json.append('"').toString();
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static byte[] loadPage() throws IOException {
        try (InputStream page = WebServer.class.getResourceAsStream("/index.html")) {
            if (page == null) {
                throw new IOException("index.html is missing from the application resources");
            }
            return page.readAllBytes();
        }
    }
}
