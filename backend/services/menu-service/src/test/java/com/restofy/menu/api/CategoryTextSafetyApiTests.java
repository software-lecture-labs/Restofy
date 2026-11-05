package com.restofy.menu.api;

import com.jayway.jsonpath.JsonPath;
import com.restofy.menu.domain.enums.ActorRole;
import com.restofy.menu.support.TestActorConfiguration;
import com.restofy.menu.support.TestActorProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A category name with NUL, a control character or a lone surrogate is a 400 (it used to be a server error). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestActorConfiguration.class)
class CategoryTextSafetyApiTests {

    private static final UUID RESTAURANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final String C = "/api/v1/menu/categories";
    private static final String S = "/api/v1/menu/subcategories";

    @Autowired MockMvc mvc;
    @Autowired TestActorProvider actor;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void admin() {
        actor.actAs(RESTAURANT, ActorRole.ADMIN);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM categories WHERE parent_id IS NOT NULL");
        jdbc.update("DELETE FROM categories");
        actor.actAsNobody();
    }

    /** A JSON string literal; control characters and surrogates travel as \\uXXXX escapes like a real client. */
    private static String json(String text) {
        var out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20 || Character.isSurrogate(c)) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private UUID created(String path, String name) throws Exception {
        String body = send(post(path), "{\"name\":" + json(name) + "}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private int categories() {
        return jdbc.queryForObject("SELECT count(*) FROM categories", Integer.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\u0000b", "a\u0001b", "a\u001fb", "a\u007fb", "a\u0085b", "a\u009fb", "a\ud800b", "\udc00", "ab\ud83d"})
    void rootAndSubcategoryCreateWithUnsafeCharactersIs400AndWritesNothing(String name) throws Exception {
        send(post(C), "{\"name\":" + json(name) + "}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Category name contains characters that are not allowed."));
        assertEquals(0, categories());
        UUID root = created(C, "Drinks");
        send(post(C + "/" + root + "/subcategories"), "{\"name\":" + json(name) + "}").andExpect(status().isBadRequest());
        assertEquals(1, categories());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\u0000b", "a\u0001b", "a\u007fb", "a\ud800b"})
    void rootAndSubcategoryRenameWithUnsafeCharactersIs400AndKeepsTheNameAndVersion(String name) throws Exception {
        UUID root = created(C, "Drinks");
        UUID child = created(C + "/" + root + "/subcategories", "Hot");
        send(patch(C + "/" + root), "{\"name\":" + json(name) + ",\"version\":0}").andExpect(status().isBadRequest());
        send(patch(S + "/" + child), "{\"name\":" + json(name) + ",\"version\":0}").andExpect(status().isBadRequest());
        assertEquals("Drinks", jdbc.queryForObject("SELECT name FROM categories WHERE id = ?", String.class, root));
        assertEquals("Hot", jdbc.queryForObject("SELECT name FROM categories WHERE id = ?", String.class, child));
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM categories WHERE id = ?", Long.class, root));
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM categories WHERE id = ?", Long.class, child));
    }

    @Test
    void theErrorBodyIsAFixedProblemWithoutInternalsOrTheSubmittedValue() throws Exception {
        String body = send(post(C), "{\"name\":\"SECRET-MARKER\\u0000\"}").andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("SECRET-MARKER"));
        assertFalse(body.toLowerCase().contains("exception"));
        assertFalse(body.toLowerCase().contains("sql"));
    }

    @Test
    void normalNamesBehaveExactlyAsBefore() throws Exception {
        for (String name : new String[] {"Çorbalar", "İçecekler & Şerbetler", "寿司", "Dessert 🍰"}) {
            send(post(C), "{\"name\":" + json(name) + "}").andExpect(status().isCreated()).andExpect(jsonPath("$.name").value(name));
        }
        send(post(C), "{\"name\":\"  Main \\t  Dishes \"}").andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Main Dishes"));
        send(post(C), "{\"name\":\"main dishes\"}").andExpect(status().isConflict());
        send(post(C), "{\"name\":\"   \"}").andExpect(status().isBadRequest());
        send(post(C), "{\"name\":\"" + "n".repeat(151) + "\"}").andExpect(status().isBadRequest());
        assertEquals(5, categories());
    }
}
