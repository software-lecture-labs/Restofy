package com.restofy.menu.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The answer of the security filters is one fixed RFC 9457 document per status and is never written twice. */
class SecurityProblemsTests {

    @Test
    void unauthenticatedIsAFixed401Problem() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SecurityProblems.unauthenticated(response);
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/problem+json"));
        assertEquals("{\"type\":\"about:blank\",\"title\":\"Unauthenticated\",\"status\":401,"
                + "\"detail\":\"Authentication is required.\"}", response.getContentAsString());
    }

    @Test
    void forbiddenIsAFixed403Problem() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SecurityProblems.forbidden(response);
        assertEquals(403, response.getStatus());
        assertEquals("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,"
                + "\"detail\":\"The caller is not allowed to perform this operation.\"}", response.getContentAsString());
    }

    @Test
    void aCommittedResponseIsLeftAlone() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        response.getWriter().write("already sent");
        response.flushBuffer();
        SecurityProblems.unauthenticated(response);
        SecurityProblems.forbidden(response);
        assertEquals(200, response.getStatus());
        assertEquals("already sent", response.getContentAsString());
    }
}
