package com.blur.client;

import com.blur.BlurMod;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tiny loopback-only HTTP server that the dashboard connects to. Uses only the
 * JDK's built-in {@code com.sun.net.httpserver} (no extra mod dependencies).
 *
 * <ul>
 *   <li>{@code GET /}        -> serves the dashboard HTML (the "premium GUI").</li>
 *   <li>{@code GET /events}  -> a Server-Sent Events stream pushing a fresh
 *       {@link StatEngine#snapshotJson()} every {@value #SSE_INTERVAL_MS} ms.</li>
 * </ul>
 *
 * Bound to 127.0.0.1 only, so nothing on the network can reach it.
 */
public final class WebServer {

	private static final int BASE_PORT = 7896;
	private static final int PORT_TRIES = 12;
	private static final long SSE_INTERVAL_MS = 100L;
	private static final String GUI_RESOURCE = "/assets/blur/gui/index.html";

	private final StatEngine engine;
	private final AtomicInteger clients = new AtomicInteger();
	private volatile boolean running = false;
	private HttpServer server;
	private int port = -1;

	public WebServer(StatEngine engine) {
		this.engine = engine;
	}

	/** @return the URL the dashboard is served at, or null if it failed to start. */
	public String start() {
		for (int i = 0; i < PORT_TRIES; i++) {
			int tryPort = BASE_PORT + i;
			try {
				server = HttpServer.create(new InetSocketAddress("127.0.0.1", tryPort), 0);
				// Cached pool + daemon threads: each SSE connection blocks its own
				// thread in a push loop, so the default (synchronous) executor won't do.
				server.setExecutor(Executors.newCachedThreadPool(r -> {
					Thread t = new Thread(r, "blur-http");
					t.setDaemon(true);
					return t;
				}));
				server.createContext("/", this::handleRoot);
				server.createContext("/events", this::handleEvents);
				running = true;
				server.start();
				port = tryPort;
				BlurMod.LOGGER.info("Blur dashboard at {}", getUrl());
				return getUrl();
			} catch (IOException e) {
				// Port busy -> try the next one.
				server = null;
			}
		}
		Diagnostics.note("Could not bind any port in %d..%d - dashboard unavailable",
				BASE_PORT, BASE_PORT + PORT_TRIES - 1);
		BlurMod.LOGGER.warn("Blur could not bind any port {}..{}", BASE_PORT, BASE_PORT + PORT_TRIES - 1);
		return null;
	}

	public boolean isRunning() {
		return running && server != null;
	}

	public int getPort() {
		return port;
	}

	/** How many Blur app / browser dashboards are currently streaming. */
	public int getClientCount() {
		return clients.get();
	}

	public void stop() {
		running = false;
		if (server != null) {
			server.stop(0);
			server = null;
		}
	}

	public String getUrl() {
		return port > 0 ? "http://localhost:" + port : null;
	}

	private void handleRoot(HttpExchange ex) throws IOException {
		if (!"GET".equals(ex.getRequestMethod())) {
			ex.sendResponseHeaders(405, -1);
			ex.close();
			return;
		}
		ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
		byte[] body;
		try (InputStream in = WebServer.class.getResourceAsStream(GUI_RESOURCE)) {
			if (in == null) {
				body = "Blur: dashboard resource missing.".getBytes(StandardCharsets.UTF_8);
				ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
			} else {
				body = in.readAllBytes();
				ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
			}
		}
		ex.sendResponseHeaders(200, body.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(body);
		}
	}

	private void handleEvents(HttpExchange ex) throws IOException {
		ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
		ex.getResponseHeaders().set("Cache-Control", "no-cache");
		ex.getResponseHeaders().set("Connection", "keep-alive");
		// The desktop app's window is a different origin, so allow cross-origin reads.
		ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
		ex.sendResponseHeaders(200, 0); // open-ended stream
		OutputStream os = ex.getResponseBody();
		clients.incrementAndGet();
		try {
			while (running) {
				String frame = "data: " + engine.snapshotJson() + "\n\n";
				os.write(frame.getBytes(StandardCharsets.UTF_8));
				os.flush();
				Thread.sleep(SSE_INTERVAL_MS);
			}
		} catch (IOException e) {
			// Browser closed the tab -> normal.
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			clients.decrementAndGet();
			ex.close();
		}
	}
}
