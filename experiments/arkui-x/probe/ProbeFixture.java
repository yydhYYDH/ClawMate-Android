package com.clawmate.reuseprobe;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Local protocol fixture. Binds only loopback; never contacts a model or reads user data. */
final class ProbeFixture implements AutoCloseable {
    private final ServerSocket server;
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newCachedThreadPool(work -> {
        Thread thread = new Thread(work, "ClawMate-loopback-fixture");
        thread.setDaemon(true);
        return thread;
    });

    ProbeFixture() throws Exception {
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        workers.execute(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    connections.add(socket);
                    workers.execute(() -> respond(socket));
                } catch (Exception error) {
                    if (!server.isClosed()) android.util.Log.e("ProbeFixture", "accept failed", error);
                }
            }
        });
    }

    String baseUrl() { return "http://127.0.0.1:" + server.getLocalPort(); }

    private void respond(Socket socket) {
        try (Socket connection = socket) {
            connection.setSoTimeout(5000);
            InputStream input = connection.getInputStream();
            ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
            String headers = "";
            while (headerBytes.size() < 32000) {
                int next = input.read();
                if (next < 0) return;
                headerBytes.write(next);
                if (next == '\n') {
                    headers = headerBytes.toString("US-ASCII");
                    if (headers.endsWith("\r\n\r\n")) break;
                }
            }
            int length = -1;
            for (String line : headers.split("\r\n")) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                    length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            if (length < 0 || length > 32000) return;
            byte[] body = new byte[length];
            int read = 0;
            while (read < length) {
                int count = input.read(body, read, length - read);
                if (count < 0) return;
                read += count;
            }
            JSONObject request = new JSONObject(new String(body, StandardCharsets.UTF_8));
            if (!request.optString("model").equals("LOCAL_PROTOCOL_FIXTURE") || !request.optBoolean("stream")) return;
            String route = headers.split(" ")[1];
            OutputStream output = connection.getOutputStream();
            if (route.startsWith("/error/")) {
                write(output, "HTTP/1.1 429 Too Many Requests\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n{\"error\":{\"message\":\"fixture_rate_limited\"}}");
                return;
            }
            write(output, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n");
            if (route.startsWith("/timeout/")) { Thread.sleep(15000); return; }
            if (route.startsWith("/cancel/")) {
                write(output, "data: {\"choices\":[{\"delta\":{\"content\":\"fixture pending\"}}]}\n\n");
                Thread.sleep(15000);
                return;
            }
            String stream = "data: {\"choices\":[{\"delta\":{\"content\":\"本地协议夹具：\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"🙂\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"fixture-call\",\"type\":\"function\",\"function\":{\"name\":\"fixture_lookup\",\"arguments\":\"{\\\"query\\\":\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"fixture\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n"
                + "data: [DONE]\n\n";
            byte[] bytes = stream.getBytes(StandardCharsets.UTF_8);
            for (int offset = 0; offset < bytes.length; offset += 11) {
                output.write(bytes, offset, Math.min(11, bytes.length - offset));
                output.flush();
                Thread.sleep(9);
            }
        } catch (Exception error) {
            if (!server.isClosed() && !(error instanceof java.net.SocketException)) {
                android.util.Log.i("ProbeFixture", "fixture ended: " + error.getClass().getSimpleName());
            }
        } finally { connections.remove(socket); }
    }

    private static void write(OutputStream output, String value) throws Exception {
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    public void close() throws Exception {
        server.close();
        for (Socket connection : connections) connection.close();
        workers.shutdownNow();
    }
}
