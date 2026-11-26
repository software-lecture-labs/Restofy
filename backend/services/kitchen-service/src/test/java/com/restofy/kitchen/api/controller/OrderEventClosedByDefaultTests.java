package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.support.OrderCaller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Without its two settings the Order event receiver answers nobody. */
@SpringBootTest
@AutoConfigureMockMvc
class OrderEventClosedByDefaultTests {

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void evenAWellFormedSignedEventIsRefused() throws Exception {
		UUID orderId = UUID.randomUUID();
		byte[] body = OrderCaller.bytes(
				OrderCaller.accepted(OrderCaller.RESTAURANT, orderId, UUID.randomUUID(), 0, "not"));

		mvc.perform(post(OrderCaller.PATH).secure(true).contentType(MediaType.APPLICATION_JSON).content(body)
						.header(HttpHeaders.AUTHORIZATION, OrderCaller.authorization(OrderCaller.RESTAURANT, body)))
				.andExpect(status().isUnauthorized());
		mvc.perform(post(OrderCaller.PATH).secure(true)).andExpect(status().isUnauthorized());
		mvc.perform(get(OrderCaller.PATH).secure(true)).andExpect(status().isUnauthorized());

		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM kitchen_orders WHERE order_id = ?", Integer.class, orderId));
	}
}
