package com.restofy.menu.application.service;

import com.restofy.menu.domain.exception.InvalidProductException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Decides whether a product image URL may be stored. A product image is only a <b>reference</b>: this service
 * never fetches, follows, proxies or stores the file, so there is no outbound request and no SSRF surface here.
 *
 * <p>An image URL is accepted only if ALL of these hold:
 * <ul>
 *   <li>it is not longer than {@value #MAX_LENGTH} characters and uses only {@code A-Z a-z 0-9 - . _ ~ : / %}
 *       (so no space, backslash, {@code @}, {@code ?}, {@code #}, brackets or non-ASCII; an internationalised host
 *       must be given as punycode);</li>
 *   <li>it is {@code https} with a registered host name: no userinfo, no query, no fragment, no IP literal,
 *       no {@code localhost}/{@code .local}/{@code .internal}-style names, no single-label or trailing-dot host;</li>
 *   <li>its origin ({@code https://host[:port]}) is exactly one of the configured allowed origins; with no
 *       configured origin nothing is accepted (fail closed);</li>
 *   <li>its path has no empty, {@code .} or {@code ..} segment, no encoded {@code . / \ %} or control character, and
 *       ends in a file name with a {@code .jpg}, {@code .jpeg}, {@code .png}, {@code .webp} or {@code .avif}
 *       extension (SVG and GIF are refused).</li>
 * </ul>
 *
 * <p><b>Limit of this check:</b> the extension is only a hint. The service does not download the file, so it cannot
 * verify the real content type, size or dimensions, nor that an {@code .png} is not something else. Whoever serves
 * the file (the future storage provider) must send a correct {@code Content-Type} with {@code nosniff}, and a
 * customer client must render it only as an image (never as a document or script) with a Content-Security-Policy
 * restricting {@code img-src}. Allowed origins are an operator decision, never derived from a request.
 */
@Component
public class ProductImagePolicy {

    public static final int MAX_LENGTH = 2048;

    private static final String HOST_NOT_VALID = "Image URL host is not valid.";
    private static final String UNSAFE_CHARACTERS = "Image URL contains characters that are not allowed.";

    private static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "avif");
    /** Last host label that names a local or private network rather than a public one. */
    private static final Set<String> LOCAL_LABELS = Set.of("localhost", "local", "internal", "localdomain", "lan", "intranet", "home", "corp");
    private static final Pattern ORIGIN = Pattern.compile("(?i)https://([^/:?#@\\\\\\s]+)(?::(\\d{1,5}))?");

    private final Set<String> allowedOrigins;

    /** @param allowedOrigins comma-separated exact origins; blank means none. An invalid entry fails fast. */
    public ProductImagePolicy(@Value("${restofy.menu.image.allowed-origins:}") String allowedOrigins) {
        this.allowedOrigins = parseOrigins(allowedOrigins == null ? "" : allowedOrigins);
    }

    /** Whether any origin is configured. */
    public boolean isEnabled() {
        return !allowedOrigins.isEmpty();
    }

    /**
     * Returns the URL in its canonical form (lower-case scheme and host, default port dropped) or throws a 400
     * with a fixed message that never contains the submitted value.
     */
    public String normalize(String raw) {
        if (allowedOrigins.isEmpty()) {
            throw invalid("Product images are not enabled: no image origin is configured.");
        }
        if (raw == null || raw.isEmpty() || raw.length() > MAX_LENGTH) {
            throw invalid("Image URL must be between 1 and " + MAX_LENGTH + " characters.");
        }
        requireSafeCharacters(raw);
        URI uri = parse(raw);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw invalid("Image URL must use https.");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw invalid(HOST_NOT_VALID);
        }
        host = host.toLowerCase(Locale.ROOT);
        requirePublicHostName(host, HOST_NOT_VALID);
        int port = uri.getPort();
        if (port == 0 || port > 65535) {
            throw invalid(HOST_NOT_VALID);
        }
        String origin = origin(host, port);
        if (!allowedOrigins.contains(origin)) {
            throw invalid("Image URL origin is not allowed.");
        }
        return origin + requireImagePath(uri.getRawPath());
    }

    // ---- origins ----

    private static Set<String> parseOrigins(String configured) {
        return Arrays.stream(configured.split(",")).map(String::strip).filter(entry -> !entry.isEmpty())
                .map(ProductImagePolicy::parseOrigin).collect(Collectors.toUnmodifiableSet());
    }

    private static String parseOrigin(String entry) {
        Matcher matcher = ORIGIN.matcher(entry);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid image origin in restofy.menu.image.allowed-origins: only exact "
                    + "https://host or https://host:port entries are allowed (no wildcard, path, query or other scheme).");
        }
        String host = matcher.group(1).toLowerCase(Locale.ROOT);
        if (!isPublicHostName(host)) {
            throw new IllegalArgumentException("Invalid image origin in restofy.menu.image.allowed-origins: the host "
                    + "must be a public registered host name (no IP literal, localhost or local-network name).");
        }
        int port = matcher.group(2) == null ? -1 : Integer.parseInt(matcher.group(2));
        if (matcher.group(2) != null && (port < 1 || port > 65535)) {
            throw new IllegalArgumentException("Invalid image origin in restofy.menu.image.allowed-origins: bad port.");
        }
        return origin(host, port);
    }

    private static String origin(String host, int port) {
        return "https://" + host + (port == -1 || port == 443 ? "" : ":" + port);
    }

    /**
     * A registered, public-looking host name: 2 to 127 dot-separated labels of 1-63 characters from
     * {@code a-z 0-9 -} (not starting or ending with {@code -}), at most 253 characters in total, a last label that
     * starts with a letter (so no IP-shaped name) and is not a local-network label.
     */
    private static boolean isPublicHostName(String host) {
        if (host.isEmpty() || host.length() > 253) {
            return false;
        }
        String[] labels = host.split("\\.", -1);
        if (labels.length < 2) {
            return false;
        }
        for (String label : labels) {
            if (!isHostLabel(label)) {
                return false;
            }
        }
        String last = labels[labels.length - 1];
        return Character.isLetter(last.charAt(0)) && !LOCAL_LABELS.contains(last);
    }

    private static boolean isHostLabel(String label) {
        if (label.isEmpty() || label.length() > 63 || label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
            return false;
        }
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')) {
                return false;
            }
        }
        return true;
    }

    private static void requirePublicHostName(String host, String message) {
        if (!isPublicHostName(host)) {
            throw invalid(message);
        }
    }

    // ---- the URL itself ----

    private static void requireSafeCharacters(String url) {
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '%') {
                // The two hex digits that follow are ordinary allowed characters and are checked right here.
                requireSafePercentEncoding(url, i);
            } else if (!isAllowedCharacter(c)) {
                throw invalid(UNSAFE_CHARACTERS);
            }
        }
    }

    private static boolean isAllowedCharacter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~' || c == ':' || c == '/';
    }

    /** Only well-formed %XX that cannot smuggle a path separator, a dot, a second encoding or a control character. */
    private static void requireSafePercentEncoding(String url, int percentIndex) {
        if (percentIndex + 2 >= url.length()) {
            throw invalid(UNSAFE_CHARACTERS);
        }
        int high = Character.digit(url.charAt(percentIndex + 1), 16);
        int low = Character.digit(url.charAt(percentIndex + 2), 16);
        if (high < 0 || low < 0) {
            throw invalid(UNSAFE_CHARACTERS);
        }
        int value = high * 16 + low;
        boolean dangerous = value < 0x20 || value == 0x7f || value == '.' || value == '/' || value == '\\' || value == '%';
        if (dangerous) {
            throw invalid(UNSAFE_CHARACTERS);
        }
    }

    private static URI parse(String url) {
        try {
            URI uri = new URI(url);
            if (uri.isOpaque() || uri.getRawAuthority() == null) {
                throw invalid("Image URL must be an absolute https URL.");
            }
            return uri;
        } catch (URISyntaxException _) {
            throw invalid("Image URL is not valid.");
        }
    }

    /** Returns the raw path when it is a plain file path with an allowed image extension. */
    private static String requireImagePath(String rawPath) {
        if (rawPath == null || rawPath.length() < 2 || rawPath.charAt(0) != '/') {
            throw invalid("Image URL must point to an image file.");
        }
        String[] segments = rawPath.substring(1).split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw invalid("Image URL path is not valid.");
            }
        }
        String file = segments[segments.length - 1];
        int dot = file.lastIndexOf('.');
        String extension = dot > 0 && dot < file.length() - 1 ? file.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!EXTENSIONS.contains(extension)) {
            throw invalid("Image URL must point to a .jpg, .jpeg, .png, .webp or .avif file.");
        }
        return rawPath;
    }

    private static InvalidProductException invalid(String message) {
        return new InvalidProductException(message);
    }
}
