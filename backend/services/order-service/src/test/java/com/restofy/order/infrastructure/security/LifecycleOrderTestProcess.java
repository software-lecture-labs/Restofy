package com.restofy.order.infrastructure.security;

import com.restofy.order.OrderServiceApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Starts an isolated Order JVM against this test's PostgreSQL container. */
record LifecycleOrderTestProcess(Process process, int port) implements AutoCloseable {

    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(60);

    static LifecycleOrderTestProcess start(Path directory, String jdbcUrl, String publicKeys, String restaurant,
                                           String outboundKey)
            throws IOException, InterruptedException {
        Files.createDirectories(directory);
        Path classpathJar = directory.resolve("classpath.jar");
        Path ready = directory.resolve("port");
        Path log = directory.resolve("order.log");
        writeClasspathJar(classpathJar);
        var builder = new ProcessBuilder(javaExecutable().toString(), "-Dspring.devtools.restart.enabled=false",
                "-Djava.io.tmpdir=" + directory.toAbsolutePath(),
                "-classpath", classpathJar.toString(),
                LifecycleOrderTestProcess.class.getName(), ready.toString());
        builder.directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var environment = builder.environment();
        environment.clear();
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null) {
            environment.put("SystemRoot", systemRoot);
        }
        environment.putAll(Map.ofEntries(
                Map.entry("SPRING_PROFILES_ACTIVE", "order-qr"),
                Map.entry("SPRING_DATASOURCE_URL", jdbcUrl),
                Map.entry("SPRING_DATASOURCE_USERNAME", "test"),
                Map.entry("SPRING_DATASOURCE_PASSWORD", "test"),
                Map.entry("SERVER_ADDRESS", "127.0.0.1"),
                Map.entry("SERVER_PORT", "0"),
                Map.entry("ORDER_QR_SERVICE_PUBLIC_KEYS", publicKeys),
                Map.entry("ORDER_QR_SERVICE_RESTAURANTS", restaurant),
                Map.entry("ORDER_QR_CALLER_KEY_ID", "local-test"),
                Map.entry("ORDER_QR_CALLER_PRIVATE_KEY", outboundKey),
                Map.entry("ORDER_QR_CALLER_RESTAURANT_ID", restaurant),
                Map.entry("CORS_ALLOWED_ORIGINS", "https://guest.example"),
                Map.entry("QR_SERVICE_URL", "https://localhost")));
        Process process = builder.start();
        try {
            int port = awaitPort(process, ready, log);
            return new LifecycleOrderTestProcess(process, port);
        } catch (IOException | InterruptedException | RuntimeException failure) {
            stop(process);
            throw failure;
        }
    }

    public static void main(String[] args) throws IOException {
        var app = (WebServerApplicationContext) SpringApplication.run(OrderServiceApplication.class);
        Path ready = Path.of(args[0]);
        Path pending = ready.resolveSibling("port.pending");
        Files.writeString(pending, Integer.toString(app.getWebServer().getPort()));
        Files.move(pending, ready, StandardCopyOption.ATOMIC_MOVE);
    }

    private static Path javaExecutable() {
        Path executable = Path.of(System.getProperty("java.home"), "bin", "java");
        return Files.isRegularFile(executable) ? executable : executable.resolveSibling("java.exe");
    }

    private static void writeClasspathJar(Path jarPath) throws IOException {
        String testClasspath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String classpath = Arrays.stream(testClasspath.split(Pattern.quote(File.pathSeparator)))
                .map(entry -> Path.of(entry).toAbsolutePath().toUri().toASCIIString())
                .collect(Collectors.joining(" "));
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classpath);
        try (var jar = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
            jar.finish();
        }
    }

    private static int awaitPort(Process process, Path ready, Path log) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) {
            if (Files.exists(ready)) {
                return Integer.parseInt(Files.readString(ready));
            }
            if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
                break;
            }
        }
        throw new IllegalStateException("Order test process did not start. See " + log + "\n" + Files.readString(log));
    }

    @Override
    public void close() {
        stop(process);
    }

    private static void stop(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Order test process did not stop");
                }
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping Order test process", interrupted);
        }
    }
}
