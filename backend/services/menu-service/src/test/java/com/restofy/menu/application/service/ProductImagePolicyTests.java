package com.restofy.menu.application.service;

import com.restofy.menu.domain.exception.InvalidProductException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The image reference rules. Hosts are RFC 2606 reserved names ({@code .example}), used only here: no real or
 * invented provider is configured anywhere in the application.
 */
class ProductImagePolicyTests {

    private static final String BASE = "https://images.example";
    private final ProductImagePolicy policy = new ProductImagePolicy("https://images.example, https://cdn.example:8443");

    private InvalidProductException rejected(String url) {
        return assertThrows(InvalidProductException.class, () -> policy.normalize(url), "should be rejected: " + url);
    }

    // ---- fail closed ----

    @Test
    void withoutAnyConfiguredOriginNothingIsAccepted() {
        for (String configured : new String[] {"", "   ", " , ,", null}) {
            var none = new ProductImagePolicy(configured);
            assertFalse(none.isEnabled());
            var failure = assertThrows(InvalidProductException.class, () -> none.normalize(BASE + "/a.png"));
            assertEquals("Product images are not enabled: no image origin is configured.", failure.getMessage());
        }
        assertTrue(policy.isEnabled());
    }

    // ---- accepted and canonical form ----

    @ParameterizedTest
    @ValueSource(strings = {
            "https://images.example/a.png", "https://images.example/a.jpg", "https://images.example/a.jpeg",
            "https://images.example/a.webp", "https://images.example/a.avif", "https://images.example/dir/sub/a-b_c~d.png",
            "https://images.example/products/12345/photo.JPG", "https://images.example/a.b.png",
            "https://cdn.example:8443/a.png", "https://images.example/caf%C3%A9.png", "https://images.example/my%20photo.webp",
            "https://images.example/a%2Bb.png"})
    void exactOriginHttpsImageFilesAreAccepted(String url) {
        assertEquals(url, policy.normalize(url));
    }

    @Test
    void schemeHostAndDefaultPortAreNormalizedButThePathIsKeptAsGiven() {
        assertEquals("https://images.example/Dir/A.PNG", policy.normalize("HTTPS://Images.EXAMPLE/Dir/A.PNG"));
        assertEquals("https://images.example/a.png", policy.normalize("https://images.example:443/a.png"));
        assertEquals("https://cdn.example:8443/a.png", policy.normalize("https://CDN.example:8443/a.png"));
    }

    @Test
    void theMaximumLengthIsAcceptedAndOneMoreIsNot() {
        String prefix = BASE + "/";
        String atLimit = prefix + "a".repeat(ProductImagePolicy.MAX_LENGTH - prefix.length() - 4) + ".png";
        assertEquals(ProductImagePolicy.MAX_LENGTH, atLimit.length());
        assertEquals(atLimit, policy.normalize(atLimit));
        String tooLong = prefix + "a".repeat(ProductImagePolicy.MAX_LENGTH - prefix.length() - 3) + ".png";
        assertEquals("Image URL must be between 1 and 2048 characters.", rejected(tooLong).getMessage());
    }

    // ---- scheme ----

    @ParameterizedTest
    @ValueSource(strings = {
            "http://images.example/a.png", "ftp://images.example/a.png", "javascript:alert(1)", "javascript://images.example/a.png",
            "data:image/png;base64,AAAA", "file:///etc/passwd.png", "file://images.example/a.png", "mailto:a@images.example",
            "//images.example/a.png", "/a.png", "a.png", "images.example/a.png", "https:images.example/a.png", "https:/images.example/a.png",
            "blob:https://images.example/a.png", "wss://images.example/a.png"})
    void everyOtherSchemeAndEveryNonAbsoluteReferenceIsRejected(String url) {
        rejected(url);
    }

    // ---- userinfo, query, fragment and structural tricks ----

    @ParameterizedTest
    @ValueSource(strings = {
            "https://user@images.example/a.png", "https://user:pass@images.example/a.png", "https://images.example@evil.example/a.png",
            "https://evil.example@images.example/a.png", "https://images.example/a.png?x=1", "https://images.example/a.png?",
            "https://images.example/a.png#frag", "https://images.example/a.png#", "https://images.example/a.png?x=1#y",
            "https://images.example\\@evil.example/a.png", "https://images.example/a\\b.png", "https://images.example/a b.png",
            "https://images.example/a\tb.png", "https://images.example/a\nb.png", "https://images.example/a.png\r\n",
            "https://images.example/a;b.png", "https://images.example/a&b.png", "https://images.example/a=b.png",
            "https://images.example/a(b).png", "https://images.example/<a>.png", "https://images.example/\"a\".png"})
    void userinfoQueryFragmentSeparatorsAndWhitespaceAreRejected(String url) {
        rejected(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://images.example/çay.png", "https://images.example/日本.png", "https://imaĝes.example/a.png",
            "https://münchen.example/a.png", "https://images.example/a\u0000.png", "https://images.example/a\u007f.png",
            "https://images.example/a​.png", "https://images.example/a\ud800.png"})
    void nonAsciiControlAndInvisibleCharactersAreRejected(String url) {
        rejected(url);
    }

    // ---- hosts and origins ----

    @ParameterizedTest
    @ValueSource(strings = {
            "https://localhost/a.png", "https://localhost:8443/a.png", "https://127.0.0.1/a.png", "https://10.0.0.1/a.png",
            "https://192.168.1.1/a.png", "https://172.16.0.1/a.png", "https://169.254.169.254/a.png", "https://0.0.0.0/a.png",
            "https://[::1]/a.png", "https://[::ffff:127.0.0.1]/a.png", "https://[2001:db8::1]/a.png", "https://2130706433/a.png",
            "https://0x7f.0.0.1/a.png", "https://017700000001/a.png", "https://127.1/a.png", "https://images/a.png",
            "https://printer.local/a.png", "https://db.internal/a.png", "https://x.localhost/a.png", "https://images.example./a.png",
            "https://-images.example/a.png", "https://images-.example/a.png", "https://ima_ges.example/a.png", "https://.example/a.png",
            "https://images..example/a.png"})
    void loopbackPrivateIpLiteralLocalAndMalformedHostsAreRejected(String url) {
        rejected(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://images.example.evil.example/a.png", "https://evil.example/images.example/a.png", "https://evilimages.example/a.png",
            "https://images.example.evil/a.png", "https://xn--images.example/a.png", "https://sub.images.example/a.png",
            "https://images.examples/a.png", "https://cdn.example/a.png", "https://cdn.example:443/a.png", "https://cdn.example:8444/a.png",
            "https://images.example:8443/a.png", "https://images.example:80/a.png", "https://images.example:0/a.png",
            "https://images.example:99999/a.png", "https://images.example:abc/a.png"})
    void onlyTheExactConfiguredOriginMatchesNeverASuffixPrefixSubdomainOrOtherPort(String url) {
        rejected(url);
    }

    @Test
    void anUnconfiguredButOtherwiseValidOriginSaysOriginNotAllowed() {
        assertEquals("Image URL origin is not allowed.", rejected("https://other.example/a.png").getMessage());
        assertEquals("Image URL origin is not allowed.", rejected("https://cdn.example/a.png").getMessage());
        assertEquals("Image URL origin is not allowed.", rejected("https://images.example:8443/a.png").getMessage());
    }

    // ---- path traversal and dangerous encoding ----

    @ParameterizedTest
    @ValueSource(strings = {
            "https://images.example/../a.png", "https://images.example/a/../b.png", "https://images.example/./a.png",
            "https://images.example/a/./b.png", "https://images.example//a.png", "https://images.example/a//b.png",
            "https://images.example/a/", "https://images.example/", "https://images.example", "https://images.example/..",
            "https://images.example/a/..", "https://images.example/%2e%2e/a.png", "https://images.example/%2E%2E/a.png",
            "https://images.example/a/%2e/b.png", "https://images.example/a%2fb.png", "https://images.example/a%2Fb.png",
            "https://images.example/a%5cb.png", "https://images.example/a%5Cb.png", "https://images.example/a%00.png",
            "https://images.example/a%0d%0a.png", "https://images.example/a%1f.png", "https://images.example/a%7f.png",
            "https://images.example/a%25.png", "https://images.example/%252e%252e/a.png", "https://images.example/a%2e.png",
            "https://images.example/a%.png", "https://images.example/a%4.png", "https://images.example/a%zz.png",
            "https://images.example/a%g1.png", "https://images.example/a.png%", "https://images.example/a.png%2"})
    void pathTraversalEmptySegmentsAndDangerousEncodingsAreRejected(String url) {
        rejected(url);
    }

    // ---- file name and extension ----

    @ParameterizedTest
    @ValueSource(strings = {
            "https://images.example/a.svg", "https://images.example/a.SVG", "https://images.example/a.svgz", "https://images.example/a.gif",
            "https://images.example/a.GIF", "https://images.example/a.html", "https://images.example/a.htm", "https://images.example/a.js",
            "https://images.example/a.php", "https://images.example/a.png.php", "https://images.example/a.png.html", "https://images.example/a.bmp",
            "https://images.example/a.tiff", "https://images.example/a.ico", "https://images.example/a.pdf", "https://images.example/a",
            "https://images.example/a.", "https://images.example/.png", "https://images.example/dir.png/", "https://images.example/png",
            "https://images.example/a.pn", "https://images.example/a.pngg", "https://images.example/a.jpe", "https://images.example/a.webm",
            "https://images.example/a.png.svg"})
    void anythingButTheFiveImageExtensionsIsRejectedSvgAndGifIncluded(String url) {
        rejected(url);
    }

    @Test
    void theExtensionIsOnlyAHintTheRealContentIsNeverVerified() {
        // Documented limit: an .png URL may serve anything. The service does not download the file, so it cannot
        // tell. The serving side must send a correct Content-Type with nosniff (see the class documentation).
        assertEquals("https://images.example/not-really-an-image.png", policy.normalize("https://images.example/not-really-an-image.png"));
    }

    // ---- messages ----

    @Test
    void errorMessagesAreFixedAndNeverContainTheSubmittedValue() {
        String marker = "SECRET-MARKER-9";
        for (String url : new String[] {"http://images.example/" + marker + ".png", "https://evil.example/" + marker + ".png",
                "https://images.example/" + marker + ".svg", "https://images.example/" + marker + "/../a.png",
                "https://images.example/" + marker + "?x=1", "https://u:p@images.example/" + marker + ".png"}) {
            assertFalse(rejected(url).getMessage().contains(marker), url);
        }
    }

    // ---- configuration is validated at startup ----

    @ParameterizedTest
    @ValueSource(strings = {
            "*", "https://*.example", "*.example", "http://images.example", "ftp://images.example", "images.example",
            "https://images.example/", "https://images.example/path", "https://images.example/a.png", "https://images.example?x=1",
            "https://images.example#f", "https://user@images.example", "https://127.0.0.1", "https://localhost",
            "https://[::1]", "https://images", "https://images.local", "https://images.internal", "https://images.example:0",
            "https://images.example:65536", "https://images.example:abc", "https://images.example:", "https://images.example.",
            "https://.example", "https://images example", "https://images.example, http://other.example", "https://images.example,*",
            "https://IMAGES.example/%"})
    void anInvalidConfiguredOriginStopsTheApplicationInsteadOfBeingIgnored(String configured) {
        var failure = assertThrows(IllegalArgumentException.class, () -> new ProductImagePolicy(configured));
        assertTrue(failure.getMessage().contains("restofy.menu.image.allowed-origins"));
    }

    @Test
    void configuredOriginsAreTrimmedNormalizedAndMayRepeat() {
        var lenient = new ProductImagePolicy("  HTTPS://Images.Example ,https://images.example:443, https://cdn.example:8443,");
        assertEquals("https://images.example/a.png", lenient.normalize("https://images.example/a.png"));
        assertEquals("https://cdn.example:8443/a.png", lenient.normalize("https://cdn.example:8443/a.png"));
        assertEquals("Image URL origin is not allowed.", rejectedBy(lenient, "https://cdn.example/a.png").getMessage());
    }

    private static InvalidProductException rejectedBy(ProductImagePolicy policy, String url) {
        return assertThrows(InvalidProductException.class, () -> policy.normalize(url));
    }
}
