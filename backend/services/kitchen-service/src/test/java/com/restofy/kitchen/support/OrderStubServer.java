package com.restofy.kitchen.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * A real HTTP server on the loopback address that stands in for the Order service: it records exactly what arrives and
 * answers what a test tells it to. It proves what Kitchen SENDS and how Kitchen reacts to an answer. It proves nothing
 * about the real Order service (its idempotency, ordering or authentication).
 */
public final class OrderStubServer implements AutoCloseable {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** One request as it arrived. */
	public record Received(String method, String path, com.sun.net.httpserver.Headers headers, String body) {

		public JsonNode json() {
			return JSON.readTree(body);
		}

		public String eventId() {
			return json().get("eventId").stringValue();
		}

		public String header(String name) {
			return headers.getFirst(name);
		}
	}

	/**
	 * What to do with one request. A {@code status} of 0 closes the connection without any answer. With a {@code stall}
	 * the status line, the headers and the first half of the body arrive at once and the rest only after that time.
	 */
	public record Answer(int status, String body, Duration delay, String location, Duration stall) {

		public static Answer of(int status, String body) {
			return new Answer(status, body, Duration.ZERO, null, Duration.ZERO);
		}

		/** The request is received (the other side may have acted on it) but the answer never arrives. */
		public static Answer lost() {
			return of(0, "");
		}

		public static Answer redirect(String location) {
			return new Answer(302, "", Duration.ZERO, location, Duration.ZERO);
		}

		public Answer after(Duration wait) {
			return new Answer(status, body, wait, location, stall);
		}

		public Answer stallingInTheBody(Duration wait) {
			return new Answer(status, body, delay, location, wait);
		}
	}

	private final HttpServer server;
	private final ExecutorService executor = Executors.newCachedThreadPool();
	private final List<Received> received = new CopyOnWriteArrayList<>();
	private final CountDownLatch closing = new CountDownLatch(1);
	private volatile Function<Received, Answer> answers = OrderStubServer::acknowledge;

	public OrderStubServer() {
		try {
			server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		server.createContext("/", this::handle);
		server.setExecutor(executor);
		server.start();
	}

	public String url() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	public void answerWith(Function<Received, Answer> newAnswers) {
		answers = newAnswers;
	}

	public void answerAlways(Answer answer) {
		answers = request -> answer;
	}

	public List<Received> received() {
		return received;
	}

	/** The requests about one order, in the order they arrived (other tests' events may reach the same server). */
	public List<Received> receivedFor(Object orderId) {
		return received.stream().filter(r -> r.json().get("orderId").stringValue().equals(orderId.toString())).toList();
	}

	/** The positive answer the Order service gives for this request. */
	public static Answer acknowledge(Received request) {
		boolean cancellation = request.path().endsWith("/kitchen-cancellation-request");
		return Answer.of(200, ack(request.json().get("orderId").stringValue(), cancellation ? "REJECTED" : "APPLIED"));
	}

	public static String ack(Object orderId, String outcome) {
		return "{\"outcome\":\"" + outcome + "\",\"duplicate\":false,\"orderId\":\"" + orderId + "\"}";
	}

	/** The Order service's error body. */
	public static String error(String code) {
		return "{\"code\":\"" + code + "\",\"message\":\"stub\",\"details\":null,\"correlationId\":null}";
	}

	private void handle(HttpExchange exchange) throws IOException {
		var request = new Received(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
				exchange.getRequestHeaders(), new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		received.add(request);
		try (exchange) {
			Answer answer = answers.apply(request);
			if (!answer.delay().isZero() && closing.await(answer.delay().toMillis(), TimeUnit.MILLISECONDS)) {
				return;
			}
			if (answer.status() == 0) {
				return;
			}
			if (answer.location() != null) {
				exchange.getResponseHeaders().add("Location", answer.location());
			}
			byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(answer.status(), body.length == 0 ? -1 : body.length);
			if (body.length > 0) {
				try (OutputStream out = exchange.getResponseBody()) {
					int first = answer.stall().isZero() ? body.length : body.length / 2;
					out.write(body, 0, first);
					out.flush();
					if (first < body.length && closing.await(answer.stall().toMillis(), TimeUnit.MILLISECONDS)) {
						return;
					}
					out.write(body, first, body.length - first);
				}
			}
		} catch (InterruptedException _) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public void close() {
		closing.countDown();
		server.stop(0);
		executor.shutdownNow();
	}
}
