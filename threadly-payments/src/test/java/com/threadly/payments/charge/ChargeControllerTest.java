package com.threadly.payments.charge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = MOCK)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@ActiveProfiles("test")
class ChargeControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    @Test
    void postSuccessCardReturns200WithSucceededStatus() throws Exception {
        mvc.perform(post("/v1/charges")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("4242424242424242", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("succeeded"))
                .andExpect(jsonPath("$.last4").value("4242"))
                .andExpect(jsonPath("$.brand").value("visa"))
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.failure_code").isEmpty());
    }

    @Test
    void postDeclineCardReturns200WithFailedStatus() throws Exception {
        mvc.perform(post("/v1/charges")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("4000000000000002", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.failure_code").value("card_declined"))
                .andExpect(jsonPath("$.failure_message").isString());
    }

    @Test
    void post0119CardReturns500ApiError() throws Exception {
        mvc.perform(post("/v1/charges")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("4000000000000119", null)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.type").value("api_error"));
    }

    @Test
    void postMalformedBodyReturns400() throws Exception {
        mvc.perform(post("/v1/charges")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":0,\"currency\":\"\",\"card\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"));
    }

    @Test
    void idempotencyKeyReplayReturnsSameChargeId() throws Exception {
        String key = "e2e-idem-" + System.nanoTime();

        MvcResult first = mvc.perform(post("/v1/charges")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("4242424242424242", key)))
                .andExpect(status().isOk())
                .andReturn();

        String id1 = om.readTree(first.getResponse().getContentAsString()).get("id").asText();

        // Second call with same key but different amount — should echo the original charge back
        MvcResult second = mvc.perform(post("/v1/charges")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithAmount("4242424242424242", key, 9999L)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode secondBody = om.readTree(second.getResponse().getContentAsString());
        assertThat(secondBody.get("id").asText()).isEqualTo(id1);
        assertThat(secondBody.get("amount").asLong()).isEqualTo(4500L);
    }

    @Test
    void getExistingChargeReturnsIt() throws Exception {
        MvcResult created = mvc.perform(post("/v1/charges")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("4242424242424242", null)))
                .andExpect(status().isOk())
                .andReturn();
        String id = om.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(get("/v1/charges/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void getMissingChargeReturns404() throws Exception {
        mvc.perform(get("/v1/charges/ch_does_not_exist"))
                .andExpect(status().isNotFound());
    }

    private static String body(String card, String idem) {
        return bodyWithAmount(card, idem, 4500L);
    }

    private static String bodyWithAmount(String card, String idem, long amount) {
        String idemLine = idem == null ? "" : ",\"idempotency_key\":\"" + idem + "\"";
        return "{"
                + "\"amount\":" + amount + ","
                + "\"currency\":\"usd\","
                + "\"card\":{\"number\":\"" + card + "\",\"exp_month\":12,\"exp_year\":2030,\"cvc\":\"123\"}"
                + idemLine
                + "}";
    }
}
