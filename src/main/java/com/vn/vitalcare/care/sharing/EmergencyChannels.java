package com.vn.vitalcare.care.sharing;

import com.vn.vitalcare.care.registration.service.EsmsOtpSender;
import com.vn.vitalcare.share.phone.PhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * How an emergency reaches a phone (BF-06 Phone-call Escalation). The call is
 * simulated — no telephony provider is contracted yet; replace {@link #call}
 * with one and nothing else changes. The SMS goes through eSMS when it is
 * configured ({@code ESMS_API_KEY}), and is only logged otherwise.
 */
@Component
public class EmergencyChannels {

    private static final Logger log = LoggerFactory.getLogger(EmergencyChannels.class);

    /** What came of one attempt: {@code outcome} as stored in the call log, and a line of detail. */
    public record Outcome(String outcome, String detail) {
    }

    private final ObjectProvider<EsmsOtpSender> esms;

    public EmergencyChannels(ObjectProvider<EsmsOtpSender> esms) {
        this.esms = esms;
    }

    public Outcome call(String phone, String message) {
        log.warn("EMERGENCY CALL (simulated) to {}: {}", PhoneNumbers.mask(phone), message);
        return new Outcome("SIMULATED", "Cuộc gọi giả lập: chưa tích hợp tổng đài, đã ghi nhận đổ chuông tới " + phone);
    }

    public Outcome sms(String phone, String text) {
        EsmsOtpSender sender = esms.getIfAvailable();
        if (sender == null) {
            log.warn("EMERGENCY SMS (eSMS not configured) to {}: {}", PhoneNumbers.mask(phone), text);
            return new Outcome("NOT_SENT", "Chưa cấu hình eSMS (ESMS_API_KEY); nội dung đã ghi vào log");
        }
        try {
            String error = sender.deliver(phone, text);
            return error == null ? new Outcome("SENT", "eSMS đã nhận tin")
                    : new Outcome("FAILED", "eSMS từ chối: " + error);
        } catch (RuntimeException e) {
            return new Outcome("FAILED", "Không gọi được eSMS: " + e.getMessage());
        }
    }
}
