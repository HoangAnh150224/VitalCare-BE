package com.vn.vitalcare.it;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * FE-15 Secure Support Chat end to end: a signed-in customer writes to a clinic (MyConversationController), the
 * clinic's staff answer from their inbox (ConversationController), a guest's conversation from the public site
 * joins the same inbox (GuestChatController), images are checked by content, and each conversation is seen only by
 * its customer and its clinic.
 */
class ChatIT extends CommittedIntegrationTest {

    private String desk;

    @BeforeEach
    void staff() throws Exception {
        desk = tokenOf(DESK_1);
    }

    private Long start(String token, String text) throws Exception {
        return Long.valueOf(json(callJson(token, post("/api/my_conversations"), """
                {"clinicId":"%s","text":"%s"}""".formatted(CLINIC_1, text)).andExpect(status().is2xxSuccessful()), "$.conversation.id"));
    }

    @Test
    void cht01_customerWritesAndTheClinicInboxShowsIt() throws Exception {
        String own = customerWithProfile(newPhone());
        Long id = start(own, "Cho hỏi lịch khám chủ nhật");
        call(desk, get("/api/conversations")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(id.intValue())));
        call(desk, get("/api/conversations/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].text").value("Cho hỏi lịch khám chủ nhật"));
        assertEquals(1, count("select count(*) from message where conversation_id = ?", id));
    }

    @Test
    void cht02_staffReplyReachesTheCustomer() throws Exception {
        String own = customerWithProfile(newPhone());
        Long id = start(own, "Phòng khám mở cửa mấy giờ?");
        callJson(desk, post("/api/conversations/" + id + "/messages"), "{\"text\":\"  Từ 7h30 đến 16h30 ạ  \"}")
                .andExpect(status().is2xxSuccessful()).andExpect(jsonPath("$.text").value("Từ 7h30 đến 16h30 ạ"));
        call(own, get("/api/my_conversations/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[1].text").value("Từ 7h30 đến 16h30 ạ"))
                .andExpect(jsonPath("$.messages[1].mine").value(false));
    }

    @Test
    void cht03_otherClinicAndOtherCustomerCannotOpenIt() throws Exception {
        Long id = start(customerWithProfile(newPhone()), "Riêng tư");
        call(tokenOf(DESK_2), get("/api/conversations/" + id)).andExpect(status().isNotFound());
        call(customerWithProfile(newPhone()), get("/api/my_conversations/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void cht04_closeAndReopen() throws Exception {
        Long id = start(customerWithProfile(newPhone()), "Đã được giải đáp");
        call(desk, post("/api/conversations/" + id + "/close")).andExpect(status().isOk());
        assertEquals(1, count("select count(*) from conversation where id = ? and status = 'CLOSED'", id));
        call(desk, post("/api/conversations/" + id + "/reopen")).andExpect(status().isOk());
        assertEquals(1, count("select count(*) from conversation where id = ? and status <> 'CLOSED'", id));
    }

    /** A guest from the public site lands in the same inbox; the staff reply reaches them with their token. */
    @Test
    void cht05_guestConversationJoinsTheClinicInbox() throws Exception {
        String r = postJson("/api/public/chat", """
                {"clinicId":"%s","name":"Lan","phone":"%s","text":"Có khám bảo hiểm không?"}""".formatted(CLINIC_1, newPhone()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Long id = Long.valueOf(com.jayway.jsonpath.JsonPath.read(r, "$.conversation.id").toString());
        String token = com.jayway.jsonpath.JsonPath.read(r, "$.token");
        callJson(desk, post("/api/conversations/" + id + "/messages"), "{\"text\":\"Có ạ\"}").andExpect(status().is2xxSuccessful());
        mvc.perform(get("/api/public/chat/" + id).header("X-Chat-Token", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[*].text", hasItem("Có ạ")));
    }

    @Test
    void cht06_emptyMessageRefused_customerCannotOpenTheInbox() throws Exception {
        String own = customerWithProfile(newPhone());
        callJson(own, post("/api/my_conversations"), "{\"clinicId\":\"%s\",\"text\":\"  \"}".formatted(CLINIC_1))
                .andExpect(status().isUnprocessableContent());
        call(own, get("/api/conversations")).andExpect(status().isForbidden());
    }

    /** Images are checked by their bytes: a picture is stored, a renamed text file is refused. */
    @Test
    void cht07_imagesAreCheckedByContent() throws Exception {
        String own = customerWithProfile(newPhone());
        Long id = start(own, "Gửi ảnh đơn thuốc");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R', 0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0};
        mvc.perform(multipart("/api/my_conversations/" + id + "/images").file(new MockMultipartFile("file", "don.png", "image/png", png))
                .header("Authorization", bearer(own))).andExpect(status().is2xxSuccessful()).andExpect(jsonPath("$.image").value(true));
        mvc.perform(multipart("/api/my_conversations/" + id + "/images")
                .file(new MockMultipartFile("file", "virus.png", "image/png", "not a png".getBytes()))
                .header("Authorization", bearer(own))).andExpect(status().is4xxClientError());
    }

    @Test
    void cht08_waitingCountGrowsWithANewConversation() throws Exception {
        long before = Long.parseLong(json(call(desk, get("/api/conversations/waiting")), "$.*").replaceAll("[^0-9]", ""));
        start(customerWithProfile(newPhone()), "Cần tư vấn");
        long after = Long.parseLong(json(call(desk, get("/api/conversations/waiting")), "$.*").replaceAll("[^0-9]", ""));
        assertTrue(after > before, before + " -> " + after);
    }
}
