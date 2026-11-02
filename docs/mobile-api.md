# VitalCare API cho app mobile

Tài liệu cho nhóm mobile (`VitalCare-Mobie`). Mọi endpoint nằm dưới `https://<host>/api`; local là `http://localhost:8085/api`.
Body là JSON. Thời gian là ISO-8601 có múi giờ (`2026-11-02T08:15:00+07:00`). Thông báo lỗi trả về dạng `{ "message": "...", "errors": { "field": "..." } }`.

Mục này sẽ được bổ sung theo từng giai đoạn: sinh hiệu (FE-04), cảnh báo (FE-05), người chăm sóc và gọi khẩn (BF-06).

## 1. Đăng nhập

| | |
|---|---|
| `POST /auth/login` | `{ "phone": "0901234567", "password": "..." }` → `{ "accessToken", "refreshToken", ... }` |
| `POST /auth/refresh` | `{ "refreshToken": "..." }` → cặp token mới |
| `POST /auth/logout` | `{ "refreshToken": "..." }` |
| `GET /auth/permissions` | danh sách mã quyền của tài khoản |

Mọi request sau đó gửi header `Authorization: Bearer <accessToken>`. Khi nhận 401, gọi `/auth/refresh` một lần rồi gửi lại request.

## 2. Thiết bị của tôi (FE-02)

Bệnh nhân chỉ thấy các thiết bị đang được giao cho chính mình và cho những hồ sơ người thân mà tài khoản đó quản lý. Thiết bị của người khác trả về 404.

### `GET /my_devices`
```json
[{
  "deviceId": 3, "deviceCode": "VC-BAND-01", "deviceType": "wristband", "model": "VB-2",
  "customerId": 37, "customerName": "Trần Thu Trang",
  "assignedAt": "...", "expectedReturnAt": null,
  "connectivity": "ONLINE", "batteryLevel": 82, "firmwareVersion": "1.2.0",
  "lastSeenAt": "...", "lastSyncAt": "...", "pairedAt": "...", "pairedWithMe": true
}]
```
`GET /my_devices/{deviceCode}` trả về một thiết bị, cùng cấu trúc.

### Ghép thiết bị qua BLE
1. App quét BLE và đọc mã thiết bị (`deviceCode`) do vòng tay quảng bá.
2. `POST /my_devices/pair` với body `{ "deviceCode": "VC-BAND-01" }`.
   - **200:** ghép thành công.
   - **404:** thiết bị chưa được giao cho tài khoản này.
   - **409:** thiết bị đang ghép với điện thoại khác; phải huỷ ghép trên máy đó trước.
3. Huỷ ghép: `DELETE /my_devices/{deviceCode}/pair`.

Khi phòng khám thu hồi thiết bị, việc ghép cũng tự huỷ.

## 3. Gửi số đo (FE-03)

Có hai đường gửi, dùng chung một định dạng payload:

| Ai gửi | Cách gửi |
|---|---|
| App điện thoại (đã đăng nhập) | `POST /my_devices/{deviceCode}/readings` |
| Thiết bị hoặc gateway gửi thẳng | `POST /device_data/batch` với header `X-Device-Code` và `X-Device-Token` |
| Thiết bị qua MQTT | publish lên `vitalcare/devices/{deviceCode}/vitals` (xem mục 4) |

### Payload
```json
{
  "clientBatchId": "b5c1…",            // tuỳ chọn: mã của lần gửi này
  "pendingOnDevice": 0,                 // số bản ghi điện thoại vẫn còn giữ offline sau lần gửi này
  "battery": 82, "firmware": "1.2.0",   // tuỳ chọn
  "session": {                          // tuỳ chọn: phiên đo
    "clientSessionId": "s-9f2…", "startedAt": "…", "endedAt": null,
    "status": "IN_PROGRESS"             // IN_PROGRESS | COMPLETED | CANCELLED
  },
  "readings": [{                        // tối đa 500 bản ghi
    "clientRecordId": "r-1a2…",         // BẮT BUỘC, duy nhất theo thiết bị (nên dùng UUID)
    "measuredAt": "2026-11-02T08:15:00+07:00",
    "spo2": 97.5, "heartRate": 76, "skinTemperature": 33.4,
    "sessionId": "s-9f2…"               // tuỳ chọn: phiên đo mà bản ghi thuộc về
  }]
}
```

### Kết quả trả về
```json
{
  "batchId": 120, "status": "PARTIAL",  // SUCCESS | PARTIAL | FAILED
  "accepted": 3, "duplicates": 1, "invalid": 0, "rejected": 1,
  "records": [
    { "clientRecordId": "r-1a2…", "outcome": "ACCEPTED", "reason": null, "vitalId": 991 },
    { "clientRecordId": "r-1a3…", "outcome": "REJECTED", "reason": "Thời điểm đo ở tương lai — đồng hồ thiết bị bị lệch", "vitalId": null }
  ]
}
```

Mỗi `outcome` nghĩa là:
- **`ACCEPTED`:** đã lưu.
- **`DUPLICATE`:** bản ghi này đã có từ trước. Có thể gửi lại bao nhiêu lần cũng được, không sinh dòng trùng.
- **`INVALID`:** đã lưu nhưng giá trị nằm ngoài khoảng đo được, nên không hiển thị và không cảnh báo. Khoảng đo được: SpO₂ 50–100, nhịp tim 20–250, nhiệt độ da 25–45 °C.
- **`REJECTED`:** không lưu, lý do nằm ở `reason`. Phòng khám thấy bản ghi này ở màn "Bản ghi đồng bộ lỗi" và có thể thử lại.

Bản ghi bị `REJECTED` khi:
- thiếu `clientRecordId` hoặc `measuredAt`;
- `measuredAt` sai định dạng, nằm ở tương lai quá 5 phút, hoặc cũ hơn 30 ngày;
- không có chỉ số nào;
- thiết bị chưa được giao cho ai vào thời điểm đo;
- thiết bị đã ngừng sử dụng.

### Đồng bộ offline
- App lưu bản ghi vào hàng đợi cục bộ. Khi có mạng, gửi theo lô và đặt `pendingOnDevice` bằng số bản ghi còn lại.
- Sau mỗi lần gửi, xoá khỏi hàng đợi mọi bản ghi có `outcome` khác `REJECTED`. Bản ghi `REJECTED` đã được server giữ lại, nên app cũng không cần gửi lại.
- Nếu request lỗi mạng hoặc 5xx, gửi lại nguyên lô. Nhờ `clientRecordId`, server không lưu trùng.

## 4. MQTT (thiết bị gửi trực tiếp)

| | |
|---|---|
| Broker | EMQX: `tcp://<host>:1883` (TLS: `8883`) |
| Username / password | `deviceCode` / mã bí mật thiết bị (lấy ở trang thiết bị → "Cấp mã bí mật") |
| Publish số đo | `vitalcare/devices/{deviceCode}/vitals`, QoS 1, payload như mục 3 |
| Publish trạng thái | `vitalcare/devices/{deviceCode}/status` với `{ "battery": 80, "firmware": "1.2.0" }` |
| Nhận kết quả | subscribe `vitalcare/devices/{deviceCode}/ack`, nhận lại kết quả như mục 3 |

Mỗi thiết bị chỉ được publish lên topic của chính mình. Nếu thiết bị không gửi gì trong 5 phút, hệ thống đánh dấu OFFLINE và báo cho bệnh nhân.

### Cấu hình EMQX (Railway)
Server đọc broker theo các biến môi trường sau. Nếu không đặt `APP_MQTT_URL` thì phần MQTT tắt và chỉ còn REST.

| Biến | Ví dụ |
|---|---|
| `APP_MQTT_URL` | `tcp://emqx.railway.internal:1883` |
| `APP_MQTT_USERNAME` / `APP_MQTT_PASSWORD` | tài khoản riêng của server (superuser) |
| `APP_MQTT_HOOK_SECRET` | **bắt buộc**: chuỗi bí mật EMQX gửi kèm ở header `X-Hook-Secret`. Để trống thì server từ chối mọi lời gọi hook, không thiết bị nào đăng nhập MQTT được |

Trong EMQX:
1. **Authentication → HTTP Server:**
   - `POST https://<api-host>/api/mqtt/auth`
   - body `{"username":"${username}","password":"${password}","clientid":"${clientid}"}`
   - header `X-Hook-Secret`
2. **Authorization → HTTP Server:**
   - `POST https://<api-host>/api/mqtt/acl`
   - body `{"username":"${username}","topic":"${topic}","action":"${action}"}`
   - cùng header `X-Hook-Secret`

## 5. Giả lập thiết bị

Khi chưa có phần cứng, có hai cách tạo số đo:

**Script dòng lệnh**
```
node tools/device-simulator.mjs --code VC-BAND-01 --token <secret> --via rest --count 5 --every 2
node tools/device-simulator.mjs --code VC-BAND-01 --token <secret> --via mqtt --scenario low_spo2
node tools/device-simulator.mjs --code VC-BAND-01 --token <secret> --offline 3 --repeat
```

**Trên web**
- ADMIN vào trang thiết bị → "Giả lập dữ liệu".
- Hoặc gọi `POST /devices/{id}/simulate` với body `{ "count": 5, "scenario": "LOW_SPO2" }`.
- Các kịch bản: `NORMAL`, `LOW_SPO2`, `HIGH_HEART_RATE`, `FEVER`, `SENSOR_FAULT`.
