package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.application.service.KitchenPreparationService;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenStatusHistoryRepository;
import com.restofy.kitchen.support.OrderCaller;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.UUID;

import static com.restofy.kitchen.support.OrderCaller.RESTAURANT;
import static com.restofy.kitchen.support.OrderCaller.accepted;
import static com.restofy.kitchen.support.OrderCaller.authorization;
import static com.restofy.kitchen.support.OrderCaller.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Order service plans to send a delivery that was not confirmed again, with the same event id and the same body.
 * A refused or failed delivery must leave nothing behind, so that the same bytes are judged anew each time, and each
 * attempt carries a new credential. No real Order service is involved.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderEventRedeliveryTests {

	@DynamicPropertySource
	static void openReceiver(DynamicPropertyRegistry registry) {
		OrderCaller.configure(registry);
	}

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private KitchenPreparationService preparation;
	@MockitoSpyBean
	private KitchenStatusHistoryRepository history;

	private final UUID orderId = UUID.randomUUID();
	private final UUID eventId = UUID.randomUUID();

	private ResultActions send(byte[] body) throws Exception {
		return mvc.perform(post(OrderCaller.PATH).secure(true).contentType(MediaType.APPLICATION_JSON).content(body)
				.header(HttpHeaders.AUTHORIZATION, authorization(RESTAURANT, body)));
	}

	private int storedOrders() {
		return jdbc.queryForObject("SELECT count(*) FROM kitchen_orders WHERE restaurant_id = ? AND order_id = ?",
				Integer.class, RESTAURANT, orderId);
	}

	/** The order with its history, inbox and outbox, to show that a refused event touched none of them. */
	private Map<String, Object> everything() {
		return jdbc.queryForMap("""
				SELECT o.status, o.version, o.source_order_version, o.cancelled_at, o.cancellation_reason,
				       (SELECT count(*) FROM kitchen_status_history h WHERE h.kitchen_order_id = o.id) AS history,
				       (SELECT count(*) FROM inbox_events i
				        WHERE i.restaurant_id = o.restaurant_id AND i.order_id = o.order_id) AS inbox,
				       (SELECT count(*) FROM outbox_events e
				        WHERE e.restaurant_id = o.restaurant_id AND e.aggregate_id = o.order_id) AS outbox
				FROM kitchen_orders o WHERE o.restaurant_id = ? AND o.order_id = ?
				""", RESTAURANT, orderId);
	}

	@Test
	void afterATemporaryFailureTheSameBytesAreStoredExactlyOnce() throws Exception {
		byte[] body = bytes(accepted(RESTAURANT, orderId, eventId, 0, "not"));
		doThrow(new IllegalStateException("simulated failure while storing")).when(history).save(any());

		send(body).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("TEMPORARILY_UNAVAILABLE"));
		assertEquals(0, storedOrders(), "the order row is rolled back with the history that failed");

		reset(history);
		send(body).andExpect(status().isNoContent());
		send(body).andExpect(status().isNoContent());

		assertEquals(1, storedOrders());
		assertEquals(1L, everything().get("history"));
	}

	@Test
	void aRefusedBodyIsRefusedAgainAndDoesNotUseUpItsEventId() throws Exception {
		String complete = accepted(RESTAURANT, orderId, eventId, 0, "not");
		byte[] withoutTable = bytes(complete.replace("\"tableNumber\":\"12\",", ""));

		send(withoutTable).andExpect(status().isBadRequest());
		send(withoutTable).andExpect(status().isBadRequest());
		assertEquals(0, storedOrders());

		send(bytes(complete)).andExpect(status().isNoContent());
		assertEquals(1, storedOrders());
	}

}
