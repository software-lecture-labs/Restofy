package com.restofy.qr.infrastructure.security;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.restofy.qr.infrastructure.security.revocation.StaffRevocationStore;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Exact staff route inventory. Unknown, guest and delegated service paths remain denied by the chain. */
public final class StaffRequestFilter extends OncePerRequestFilter {
    private static final String ID = "[0-9a-fA-F-]{36}";
    private final Q5SecuritySettings settings;
    private final Clock clock;
    private final StaffRevocationStore store;

    public StaffRequestFilter(Q5SecuritySettings settings, Clock clock, StaffRevocationStore store) {
        this.settings=settings; this.clock=clock; this.store=store;
    }

    static boolean staffPath(String method, String path) { return policy(method,path) != null; }

    private static Set<String> policy(String method, String path) {
        String route = path.startsWith("/api/v1/qr") ? path.substring("/api/v1/qr".length()) : "";
        if (method.equals("GET") && (route.equals("/tables") || route.matches("/tables/"+ID+"(/session-statistics)?")
                || route.matches("/table-sessions/"+ID+"/close-commands/"+ID))) return Set.of("ADMIN","WAITER");
        if (method.equals("POST") && route.matches("/table-sessions/"+ID+"/(close|cleaned)")) return Set.of("ADMIN","WAITER");
        if ((method.equals("POST") && route.equals("/tables")) || (method.equals("PUT") && route.matches("/tables/"+ID))
                || (method.equals("PATCH") && route.matches("/tables/"+ID+"/active"))
                || (method.equals("GET") && route.matches("/tables/"+ID+"/qr-codes(/"+ID+"(/(image|download))?)?"))
                || (method.equals("POST") && route.matches("/tables/"+ID+"/qr-codes(/"+ID+"/rotate)?"))
                || (method.equals("PATCH") && route.matches("/tables/"+ID+"/qr-codes/"+ID+"/active"))
                || (method.equals("DELETE") && route.matches("/tables/"+ID+"/qr-codes/"+ID))) return Set.of("ADMIN");
        return null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        Set<String> roles = policy(request.getMethod(), request.getRequestURI());
        if (roles == null) { chain.doFilter(request,response); return; }
        Principal principal;
        try { principal=verify(request); }
        catch (RuntimeException invalid) { problem(response,401); return; }
        if (!roles.contains(principal.role())) { problem(response,403); return; }
        if (!request.getMethod().equals("GET")) {
            try { csrf(request); } catch (RuntimeException invalid) { problem(response,403); return; }
        }
        try { store.requireAllowed(principal.restaurantId(), principal.userId(), principal.sessionId()); }
        catch (org.springframework.web.server.ResponseStatusException denied) { problem(response,denied.getStatusCode().value()); return; }
        catch (RuntimeException databaseUnavailable) { problem(response,503); return; }
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,
                List.of(new SimpleGrantedAuthority("ROLE_"+principal.role()))));
        SecurityContextHolder.setContext(context);
        try { chain.doFilter(request,response); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private Principal verify(HttpServletRequest request) {
        try {
            if (request.getHeader("Authorization") != null) throw new IllegalArgumentException();
            String token=cookie(request,"restofy_access");
            if (cookie(request,"restofy_mfa")!=null || cookie(request,"restofy_refresh")!=null) throw new IllegalArgumentException();
            if (token==null || token.length()>4096) throw new IllegalArgumentException();
            var key=settings.staffKey();
            UUID restaurant=settings.restaurant();
            var claims=Jwts.parser().verifyWith(key).sig().clear().add(Jwts.SIG.RS256).and().clock(() -> Date.from(clock.instant()))
                    .requireIssuer(settings.staffIssuer()).requireAudience("restofy-staff-api")
                    .require("token_type","access").require("auth_state","AUTHENTICATED").require("auth_policy","q5-idle-v1")
                    .require("restaurant_id",restaurant.toString()).build().parseSignedClaims(token).getPayload();
            long ttl=settings.accessTtl();
            if (ttl<300 || ttl>3600 || claims.getIssuedAt()==null || claims.getNotBefore()==null || claims.getExpiration()==null || claims.getId()==null
                    || claims.getIssuedAt().toInstant().isAfter(clock.instant()) || !clock.instant().isBefore(claims.getExpiration().toInstant())
                    || !claims.getExpiration().toInstant().isAfter(claims.getIssuedAt().toInstant())
                    || claims.getExpiration().toInstant().isAfter(claims.getIssuedAt().toInstant().plusSeconds(ttl))) throw new IllegalArgumentException();
            UUID.fromString(claims.getId());
            if (!Set.of("ADMIN","WAITER","KITCHEN").contains(claims.get("role",String.class))) throw new IllegalArgumentException();
            return new Principal(restaurant,UUID.fromString(claims.getSubject()),UUID.fromString(claims.get("sid",String.class)),claims.get("role",String.class));
        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid staff identity"); }
    }

    private void csrf(HttpServletRequest request) {
        if (Collections.list(request.getHeaders("Origin")).size()!=1 || Collections.list(request.getHeaders("X-CSRF-TOKEN")).size()!=1) throw new IllegalArgumentException();
        String origin=request.getHeader("Origin");
        var origins=settings.origins();
        if (!origins.contains(origin) || origin.contains("*") || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType()))) throw new IllegalArgumentException();
        String token=cookie(request,"restofy_csrf"), header=request.getHeader("X-CSRF-TOKEN");
        if (token==null || !token.matches("[A-Za-z0-9_.-]{20,2048}") || header==null
                || !MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII),header.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) throw new IllegalArgumentException();
    }

    private static String cookie(HttpServletRequest request, String name) {
        String value=null;
        int count=0;
        if (request.getCookies()!=null) for (var cookie:request.getCookies()) if (name.equals(cookie.getName())) {
            if (++count>1) throw new IllegalArgumentException(); value=cookie.getValue();
        }
        return value;
    }

    private static void problem(HttpServletResponse response,int status) throws IOException {
        response.setStatus(status); response.setContentType("application/problem+json");
        response.getWriter().write("{\"status\":"+status+",\"title\":\"Staff authorization unavailable\"}");
    }

    public static Principal requireVerified() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if (authentication==null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof Principal staff)) {
            throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException("Verified staff required");
        }
        return staff;
    }

    public record Principal(UUID restaurantId,UUID userId,UUID sessionId,String role) { }
}
