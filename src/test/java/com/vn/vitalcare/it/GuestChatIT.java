package com.vn.vitalcare.it;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/** Chat with Clinic as a guest: /api/public/chat through SupportChat to the conversation and message tables and file storage. */
class GuestChatIT extends IntegrationTestBase {

    private static final AtomicInteger IP = new AtomicInteger((int) (System.nanoTime() % 200));

    /** Each test writes from its own address, so the per-address limits of one test never reach another. */
    private final String ip = "10.77." + (IP.incrementAndGet() % 250) + "." + (System.nanoTime() % 250);

    private ResultActions start(String clinicId, String name, String phone, String text) throws Exception {
        return mvc.perform(post("/api/public/chat").header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"clinicId":%s,"name":"%s","phone":"%s","text":"%s"}"""
                        .formatted(clinicId == null ? "null" : "\"" + clinicId + "\"", name, phone, text)));
    }

    private String[] started() throws Exception {
        ResultActions r = start(CLINIC_1, "Nguyễn Thị Lan", "0901 234 567", "Cho hỏi giờ khám").andExpect(status().isCreated());
        return new String[] {json(r, "$.conversation.id"), json(r, "$.token")};
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void it01_startStoresConversationAndFirstMessage() throws Exception {
        ResultActions r = start(CLINIC_1, "  Nguyễn Thị Lan ", "0901 234 567", "Cho hỏi giờ khám")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conversation.clinicId").value(CLINIC_1))
                .andExpect(jsonPath("$.conversation.clinicName").value("VitalCare Clinic"));
        long id = Long.parseLong(json(r, "$.conversation.id"));
        String token = json(r, "$.token");
        sync();
        Map<String, Object> row = jdbc.queryForMap("select contact_name, contact_phone, guest_token_hash, last_sender_kind, "
                + "last_message_preview from conversation where id = ?", id);
        assertEquals("Nguyễn Thị Lan", row.get("contact_name"));
        assertEquals("+84901234567", row.get("contact_phone"));
        assertNotEquals(token, row.get("guest_token_hash"));
        assertEquals(sha256(token), row.get("guest_token_hash"));
        assertEquals("GUEST", row.get("last_sender_kind"));
        assertEquals("Cho hỏi giờ khám", row.get("last_message_preview"));
        assertEquals(1, count("select count(*) from message where conversation_id = ?", id));
    }

    @Test
    void it02_tokenOpensThread() throws Exception {
        String[] c = started();
        mvc.perform(get("/api/public/chat/" + c[0]).header("X-Chat-Token", c[1]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].text").value("Cho hỏi giờ khám"))
                .andExpect(jsonPath("$.messages[0].mine").value(true));
    }

    @Test
    void it03_wrongOrMissingTokenIs404() throws Exception {
        String[] c = started();
        mvc.perform(get("/api/public/chat/" + c[0]).header("X-Chat-Token", c[1] + "x"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Conversation with id " + c[0] + " was not found"));
        mvc.perform(get("/api/public/chat/" + c[0])).andExpect(status().isNotFound());
    }

    @Test
    void it04_sendAppendsAndUpdatesPreview() throws Exception {
        String[] c = started();
        mvc.perform(post("/api/public/chat/" + c[0] + "/messages").header("X-Chat-Token", c[1]).header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"  Còn một câu nữa  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Còn một câu nữa"));
        assertEquals(2, count("select count(*) from message where conversation_id = ?", Long.parseLong(c[0])));
        sync();
        assertEquals("Còn một câu nữa", jdbc.queryForObject("select last_message_preview from conversation where id = ?",
                String.class, Long.parseLong(c[0])));
    }

    @Test
    void it05_badPhone422() throws Exception {
        start(CLINIC_1, "Lan", "12", "hi")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.phone").value("Give a valid phone number"));
    }

    @Test
    void it06_unknownOrClosedClinic422() throws Exception {
        start(UUID.randomUUID().toString(), "Lan", "0901234567", "hi")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.clinicId").value("Pick one of the clinics"));
        jdbc.update("update clinic set operating_status = 'INACTIVE' where clinic_id = ?::uuid", CLINIC_1);
        sync();
        start(CLINIC_1, "Lan", "0901234567", "hi")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.clinicId").value("That clinic is not taking messages"));
    }

    @Test
    void it07_missingFields422() throws Exception {
        start(null, "", "", "")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.clinicId").value("Pick a clinic"))
                .andExpect(jsonPath("$.errors.name").value("Give your name"))
                .andExpect(jsonPath("$.errors.phone").value("Give a phone number the clinic can call back"))
                .andExpect(jsonPath("$.errors.text").value("Write a message"));
    }

    @Test
    void it08_imageStoredAndServedBack() throws Exception {
        String[] c = started();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
        String messageId = json(mvc.perform(multipart("/api/public/chat/" + c[0] + "/images")
                        .file(new MockMultipartFile("file", "a.png", "image/png", png))
                        .header("X-Chat-Token", c[1]).header("X-Forwarded-For", ip))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.image").value(true)), "$.id");
        byte[] back = mvc.perform(get("/api/public/chat/" + c[0] + "/images/" + messageId).header("X-Chat-Token", c[1]))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("image/png")))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(png, back);
    }

    @Test
    void it09_nonImageRefused() throws Exception {
        String[] c = started();
        mvc.perform(multipart("/api/public/chat/" + c[0] + "/images")
                        .file(new MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF-1.4".getBytes()))
                        .header("X-Chat-Token", c[1]).header("X-Forwarded-For", ip))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.file").value("Only PNG and JPEG images are accepted"));
    }

    @Test
    void it10_fiveStartsPerHourPerAddress() throws Exception {
        for (int i = 0; i < 5; i++) {
            start(CLINIC_1, "Lan", "0901234567", "hi " + i).andExpect(status().isCreated());
        }
        start(CLINIC_1, "Lan", "0901234567", "hi 6")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith(
                        "Too many conversations have been started from this network")));
    }
}
