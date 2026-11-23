package com.restofy.qr.infrastructure.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.restofy.qr.infrastructure.security.revocation.StaffRevocationStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

/** Exact staff route inventory. Unknown, guest and delegated service paths remain denied by the chain. */
public final class StaffRequestFilter extends OncePerRequestFilter {
    private static final String ID = "[0-9a-fA-F-]{36}";
    private static final String PREFIX = "/api/v1/qr";
    private static final String TABLES = "/tables";
    private static final String TABLE = TABLES + "/" + ID;
    private static final String ADMIN = "ADMIN";
    private static final String WAITER = "WAITER";
    private static final String GET = "GET";
    private static final String POST = "POST";
    private static final String PATCH = "PATCH";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";
    private static final Set<String> NONE = Set.of();
    private static final Set<String> FLOOR = Set.of(ADMIN, WAITER);
    private static final Set<String> ADMIN_ONLY = Set.of(ADMIN);

    private final Q5SecuritySettings settings;
    private final StaffTokenVerifier verifier;
    private final Clock clock;
    private final StaffRevocationStore store;

    public StaffRequestFilter(Q5SecuritySettings settings, Clock clock, StaffRevocationStore store) {
        this.settings=settings; this.clock=clock; this.store=store; this.verifier=new StaffTokenVerifier(settings, clock, store);
    }

    static boolean staffPath(String method, String path) { return !policy(method,path).isEmpty(); }

    /** Empty means: not a staff route, the chain decides (default deny). */
    private static Set<String> policy(String method, String path) {
        String route = path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : "";
        if (floorRoute(method, route)) return FLOOR;
        if (tableAdminRoute(method, route) || qrCodeAdminRoute(method, route)) return ADMIN_ONLY;
        return NONE;
    }

    private static boolean floorRoute(String method, String route) {
        if (GET.equals(method)) {
            return route.equals(TABLES) || route.matches(TABLE+"(/session-statistics)?")
                    || route.matches("/table-sessions/"+ID+"/close-commands/"+ID);
        }
        return POST.equals(method) && route.matches("/table-sessions/"+ID+"/(close|cleaned)");
    }

    private static boolean tableAdminRoute(String method, String route) {
        return switch (method) {
            case POST -> route.equals(TABLES);
            case "PUT" -> route.matches(TABLE);
            case PATCH -> route.matches(TABLE+"/active");
            default -> false;
        };
    }

    private static boolean qrCodeAdminRoute(String method, String route) {
        String codes = TABLE+"/qr-codes";
        return switch (method) {
            case GET -> route.matches(codes+"(/"+ID+"(/(image|download))?)?");
            case POST -> route.matches(codes+"(/"+ID+"/rotate)?");
            case PATCH -> route.matches(codes+"/"+ID+"/active");
            case "DELETE" -> route.matches(codes+"/"+ID);
            default -> false;
        };
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        Set<String> roles = policy(request.getMethod(), request.getRequestURI());
        if (roles.isEmpty()) { chain.doFilter(request,response); return; }
        Principal principal;
        try { principal=verify(request); }
        catch (RuntimeException _) { problem(response,401); return; }
        if (!roles.contains(principal.role())) { problem(response,403); return; }
        if (!request.getMethod().equals(GET)) {
            try { csrf(request); } catch (RuntimeException _) { problem(response,403); return; }
        }
        try { store.requireAllowed(principal.restaurantId(), principal.userId(), principal.sessionId()); }
        catch (ResponseStatusException denied) { problem(response,denied.getStatusCode().value()); return; }
        catch (RuntimeException _) { problem(response,503); return; }
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,
                List.of(new SimpleGrantedAuthority("ROLE_"+principal.role()))));
        SecurityContextHolder.setContext(context);
        try { chain.doFilter(request,response); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private Principal verify(HttpServletRequest request) {
        try {
            if (request.getHeader(HttpHeaders.AUTHORIZATION) != null) throw new IllegalArgumentException();
            String token=cookie(request,"restofy_access");
            if (cookie(request,"restofy_mfa")!=null || cookie(request,"restofy_refresh")!=null) throw new IllegalArgumentException();
            if (token==null) throw new IllegalArgumentException();
            return verifier.verify(token);
        } catch (Exception _) { throw new IllegalArgumentException("Invalid staff identity"); }
    }

    private void csrf(HttpServletRequest request) {
        if (Collections.list(request.getHeaders(HttpHeaders.ORIGIN)).size()!=1 || Collections.list(request.getHeaders(CSRF_HEADER)).size()!=1) throw new IllegalArgumentException();
        String origin=request.getHeader(HttpHeaders.ORIGIN);
        var origins=settings.origins();
        if (!origins.contains(origin) || origin.contains("*") || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType()))) throw new IllegalArgumentException();
        String token=cookie(request,"restofy_csrf");
        String header=request.getHeader(CSRF_HEADER);
        if (token==null || !token.matches("[A-Za-z0-9_.-]{20,2048}") || header==null
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII),header.getBytes(StandardCharsets.US_ASCII))) throw new IllegalArgumentException();
    }

    private static String cookie(HttpServletRequest request, String name) {
        if (request.getCookies()==null) return null;
        String value=null;
        int count=0;
        for (var cookie:request.getCookies()) {
            if (!name.equals(cookie.getName())) continue;
            if (++count>1) throw new IllegalArgumentException();
            value=cookie.getValue();
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
