package com.restofy.qr.infrastructure;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.restofy.qr.infrastructure.security.revocation.StaffRevocationStore;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class RevocationInternalHttpTests {
    private static final KeyPair AUTH = keys();
    private static final KeyPair QR = keys();
    private static final KeyPair STAFF = keys();
    private static final UUID RESTAURANT = UUID.randomUUID();
    @Autowired WebApplicationContext context;
    @Autowired StaffRevocationStore store;
    @Autowired JdbcTemplate jdbc;
    private MockMvc http;
    private UUID generation;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:tc:q5postgresql:17-alpine:///restofy_test");
        properties.add("qr.q5.restaurant-id", RESTAURANT::toString);
        properties.add("qr.q5.auth-public-key", () -> Base64.getEncoder().encodeToString(AUTH.getPublic().getEncoded()));
        properties.add("qr.q5.receipt-private-key", () -> Base64.getEncoder().encodeToString(QR.getPrivate().getEncoded()));
        properties.add("qr.q5.receipt-public-key", () -> Base64.getEncoder().encodeToString(QR.getPublic().getEncoded()));
        properties.add("qr.q5.staff-public-key", () -> Base64.getEncoder().encodeToString(STAFF.getPublic().getEncoded()));
        properties.add("qr.q5.staff-issuer", () -> "restofy-auth");
        properties.add("qr.q5.access-ttl-seconds", () -> "900");
        properties.add("qr.q5.allowed-origins", () -> " https://restofy.example , https://second.restofy.example ");
    }

    @BeforeEach
    void setup() {
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("DELETE FROM staff_revocation_receipts");
        jdbc.update("DELETE FROM staff_revoked_sessions");
        generation = UUID.randomUUID();
        beginBootstrapFixture(generation);
    }

    @Test
    void baselineBindsCurrentDatabaseNonceAndOpensOnlyAfterPersistingAllFences() throws Exception {
        UUID user=UUID.randomUUID(), sid=UUID.randomUUID();
        var challenge=store.challenge();
        var baseline=new StaffRevocationStore.Baseline(UUID.randomUUID(),RESTAURANT,generation,challenge.nonce(),
                List.of(new StaffRevocationStore.Entry(sid,user,Instant.parse("2030-01-01T00:00:00Z"))));
        String body=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(baseline);
        http.perform(post("/internal/q5/bootstrap").contentType("application/json").content(body)
                .header("Authorization",bootstrapCredential(baseline))).andExpect(status().isOk());
        assertThatThrownBy(() -> store.requireAllowed(RESTAURANT,user,sid)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        store.requireAllowed(RESTAURANT,user,UUID.randomUUID());
        jdbc.update("UPDATE staff_revocation_guard_state SET database_start=database_start-interval '1 second'");
        UUID newNonce=store.challenge().nonce();
        assertThat(newNonce).isNotEqualTo(challenge.nonce());
        http.perform(post("/internal/q5/bootstrap").contentType("application/json").content(body)
                .header("Authorization",bootstrapCredential(baseline))).andExpect(status().isServiceUnavailable());
        assertThatThrownBy(() -> store.requireAllowed(RESTAURANT,user,UUID.randomUUID())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void revokedJwtCannotReachAnyOfTheSeventeenStaffMethodPaths() throws Exception {
        var command=command();
        store.apply(command); completeBootstrapFixture(generation);
        String jwt=staff(command.userId(),command.targets().getFirst().sessionId(),"ADMIN",RESTAURANT,"restofy-auth",true);
        String id=UUID.randomUUID().toString();
        var paths=List.of("POST /tables","PUT /tables/"+id,"PATCH /tables/"+id+"/active","GET /tables","GET /tables/"+id,
                "GET /tables/"+id+"/session-statistics","POST /tables/"+id+"/qr-codes","POST /tables/"+id+"/qr-codes/"+id+"/rotate",
                "PATCH /tables/"+id+"/qr-codes/"+id+"/active","DELETE /tables/"+id+"/qr-codes/"+id,
                "GET /tables/"+id+"/qr-codes","GET /tables/"+id+"/qr-codes/"+id,"GET /tables/"+id+"/qr-codes/"+id+"/image",
                "GET /tables/"+id+"/qr-codes/"+id+"/download","POST /table-sessions/"+id+"/close",
                "GET /table-sessions/"+id+"/close-commands/"+id,"POST /table-sessions/"+id+"/cleaned");
        for (String path:paths) {
            String[] parts=path.split(" ");
            var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(parts[0]),"/api/v1/qr"+parts[1])
                    .cookie(new jakarta.servlet.http.Cookie("restofy_access",jwt),new jakarta.servlet.http.Cookie("restofy_csrf","fixture_csrf_value_1234567890"))
                    .header("Origin","https://restofy.example").header("X-CSRF-TOKEN","fixture_csrf_value_1234567890")
                    .contentType("application/json").content("{}");
            http.perform(request).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void localJwtRoleTenantLegacyPolicyAndCsrfAreEnforced() throws Exception {
        completeBootstrapFixture(generation);
        UUID user=UUID.randomUUID(),sid=UUID.randomUUID();
        String waiter=staff(user,sid,"WAITER",RESTAURANT,"restofy-auth",true);
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/qr/tables")
                .cookie(new jakarta.servlet.http.Cookie("restofy_access",waiter))).andExpect(status().isOk());
        for (String ambiguous:List.of("restofy_access","restofy_mfa","restofy_refresh")) {
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/qr/tables")
                    .cookie(new jakarta.servlet.http.Cookie("restofy_access",waiter),new jakarta.servlet.http.Cookie(ambiguous,"ambiguous-fixture")))
                    .andExpect(status().isUnauthorized());
        }
        for (String invalid:List.of(staff(user,sid,"ADMIN",UUID.randomUUID(),"restofy-auth",true),
                staff(user,sid,"ADMIN",RESTAURANT,"wrong-issuer",true),staff(user,sid,"ADMIN",RESTAURANT,"restofy-auth",false))) {
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/qr/tables")
                    .cookie(new jakarta.servlet.http.Cookie("restofy_access",invalid))).andExpect(status().isUnauthorized());
        }
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/qr/tables")
                .cookie(new jakarta.servlet.http.Cookie("restofy_access",staff(user,sid,"KITCHEN",RESTAURANT,"restofy-auth",true))))
                .andExpect(status().isForbidden());
        http.perform(post("/api/v1/qr/tables").cookie(new jakarta.servlet.http.Cookie("restofy_access",waiter))
                .contentType("application/json").content("{\"tableNumber\":\"denied\"}")).andExpect(status().isForbidden());
        String admin=staff(user,sid,"ADMIN",RESTAURANT,"restofy-auth",true);
        http.perform(post("/api/v1/qr/tables").cookie(new jakarta.servlet.http.Cookie("restofy_access",admin))
                .contentType("application/json").content("{\"tableNumber\":\"denied\"}")).andExpect(status().isForbidden());
        http.perform(post("/api/v1/qr/tables").cookie(new jakarta.servlet.http.Cookie("restofy_access",admin),
                        new jakarta.servlet.http.Cookie("restofy_csrf","fixture_csrf_value_1234567890"))
                .header("Origin","https://restofy.example").header("X-CSRF-TOKEN","fixture_csrf_value_1234567890")
                .contentType("application/json").content("{\"tableNumber\":\""+UUID.randomUUID()+"\"}")).andExpect(status().isCreated());
        jdbc.update("DELETE FROM staff_revocation_guard_state");
        try {
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/qr/tables")
                    .cookie(new jakarta.servlet.http.Cookie("restofy_access",waiter))).andExpect(status().isServiceUnavailable());
        } finally { jdbc.update("INSERT INTO staff_revocation_guard_state(singleton) VALUES(true)"); }
    }

    private String staff(UUID user,UUID sid,String role,UUID restaurant,String issuer,boolean policy) {
        Instant now=Instant.now();
        var builder=Jwts.builder().issuer(issuer).subject(user.toString()).audience().add("restofy-staff-api").and()
                .issuedAt(Date.from(now)).notBefore(Date.from(now)).expiration(Date.from(now.plusSeconds(900))).id(UUID.randomUUID().toString())
                .claim("sid",sid.toString()).claim("role",role).claim("restaurant_id",restaurant.toString())
                .claim("token_type","access").claim("auth_state","AUTHENTICATED");
        if (policy) builder.claim("auth_policy","q5-idle-v1");
        return builder.signWith(STAFF.getPrivate(),Jwts.SIG.RS256).compact();
    }

    private String bootstrapCredential(StaffRevocationStore.Baseline baseline) {
        Instant now=Instant.now();
        return "Bearer "+Jwts.builder().issuer("auth-service").subject(baseline.operationId().toString()).audience().add("restofy-qr-revocation").and()
                .issuedAt(Date.from(now)).notBefore(Date.from(now)).expiration(Date.from(now.plusSeconds(30))).id(UUID.randomUUID().toString())
                .claim("token_type","service").claim("scope","staff:bootstrap:write").claim("restaurant_id",RESTAURANT.toString())
                .claim("generation",generation.toString()).claim("nonce",baseline.nonce().toString())
                .claim("manifest",StaffRevocationStore.baselineDigest(baseline)).signWith(AUTH.getPrivate(),Jwts.SIG.RS256).compact();
    }

    @Test
    void signsReceiptOnlyAfterDurableFenceAndDuplicateReturnsSameManifest() throws Exception {
        var command = command();
        String result = http.perform(request(command).header("Authorization", credential(command, "auth-service", "staff:revocation:write")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_revocation_receipts WHERE operation_id=?", Long.class, command.operationId())).isEqualTo(1);
        String receipt = tools.jackson.databind.json.JsonMapper.builder().build().readTree(result).get("receipt").asText();
        var claims = Jwts.parser().verifyWith(QR.getPublic()).requireIssuer("qr-service").requireAudience("restofy-auth")
                .requireSubject(command.operationId().toString()).require("manifest", StaffRevocationStore.digest(command))
                .build().parseSignedClaims(receipt).getPayload();
        assertThat(claims.get("generation", String.class)).isEqualTo(generation.toString());
        completeBootstrapFixture(generation);
        assertThatThrownBy(() -> store.requireAllowed(RESTAURANT, command.userId(), command.targets().getFirst().sessionId()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        http.perform(request(command).header("Authorization", credential(command, "auth-service", "staff:revocation:write")))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_revocation_receipts", Long.class)).isEqualTo(1);
    }

    @Test
    void rejectsPublicBrowserStaffAndWrongServiceScopeWithoutWriting() throws Exception {
        var command = command();
        http.perform(request(command)).andExpect(status().isUnauthorized());
        http.perform(request(command).header("Authorization", credential(command, "staff", "staff:revocation:write")))
                .andExpect(status().isUnauthorized());
        http.perform(request(command).header("Authorization", credential(command, "auth-service", "staff:read")))
                .andExpect(status().isUnauthorized());
        http.perform(request(command).header("Authorization",credential(command,"auth-service","staff:revocation:write","wrong-audience",AUTH)))
                .andExpect(status().isUnauthorized());
        http.perform(request(command).header("Authorization",credential(command,"auth-service","staff:revocation:write","restofy-qr-revocation",STAFF)))
                .andExpect(status().isUnauthorized());
        http.perform(request(command).header("Authorization", credential(command, "auth-service", "staff:revocation:write"))
                .header("Origin", "https://example.invalid")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_revocation_receipts", Long.class)).isZero();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(StaffRevocationStore.Command command) {
        return post("/internal/q5/revocations").contentType("application/json")
                .content(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(command));
    }

    private StaffRevocationStore.Command command() {
        return new StaffRevocationStore.Command(UUID.randomUUID(), RESTAURANT, UUID.randomUUID(), 1, generation,
                List.of(new StaffRevocationStore.Target(UUID.randomUUID(), Instant.parse("2030-01-01T00:00:00Z"))));
    }

    private String credential(StaffRevocationStore.Command command, String issuer, String scope) {
        return credential(command,issuer,scope,"restofy-qr-revocation",AUTH);
    }

    private String credential(StaffRevocationStore.Command command,String issuer,String scope,String audience,KeyPair signer) {
        Instant now = Instant.now();
        return "Bearer " + Jwts.builder().issuer(issuer).subject(command.operationId().toString())
                .audience().add(audience).and().issuedAt(Date.from(now)).notBefore(Date.from(now))
                .expiration(Date.from(now.plusSeconds(30))).id(UUID.randomUUID().toString())
                .claim("scope", scope).claim("token_type", "service").claim("restaurant_id", RESTAURANT.toString())
                .claim("generation", generation.toString()).claim("manifest", StaffRevocationStore.digest(command))
                .signWith(signer.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private static KeyPair keys() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    // Explicit test-only state fixtures. Production readiness is opened only by signed full-baseline installation.
    private void beginBootstrapFixture(UUID generation) {
        jdbc.update("UPDATE staff_revocation_guard_state SET ready=false,generation=?,database_start=NULL,bootstrap_nonce=NULL WHERE singleton=true",generation);
    }

    private void completeBootstrapFixture(UUID generation) {
        jdbc.update("UPDATE staff_revocation_guard_state SET generation=?,ready=true,database_start=pg_postmaster_start_time(),bootstrap_nonce=? WHERE singleton=true",generation,UUID.randomUUID());
    }

}
