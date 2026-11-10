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

## 2. Đăng ký bằng số điện thoại

Đăng ký tự phục vụ yêu cầu xác minh số điện thoại trước khi tạo tài khoản:

| | |
|---|---|
| `POST /auth/register/otp` | `{ "phone": "0901234567" }` → `{ "expiresIn": 300, "resendAfter": 60 }` |
| `POST /auth/register` | `{ "phone": "0901234567", "otp": "123456", "fullName": "Nguyen Van A", "password": "..." }` → cặp token mới |

Request đăng ký cuối chỉ tạo user và hồ sơ khách hàng khi OTP còn hạn, chưa dùng và đúng với số điện thoại đã gửi mã. Mã sai, hết hạn, đã dùng hoặc vượt số lần thử trả lỗi tại `errors.otp`; request OTP áp dụng giới hạn theo số điện thoại và địa chỉ IP. `email` là trường tùy chọn.

## 3. Thiết bị của tôi (FE-02)

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

### Gửi nguyên log của vòng tay (`#v2`)
Vòng tay có thể gửi thẳng log của nó, không cần chuyển sang JSON:
- **REST:** `POST /device_data/batch`, header `Content-Type: text/csv` cùng `X-Device-Code` và `X-Device-Token`.
- **MQTT:** publish lên `vitalcare/devices/{deviceCode}/vitals`. Server nhận ra log khi payload bắt đầu bằng `#v2`.
```
#v2
ts,t100,hr,spo2,conf,flags
1793153993,3278,62,98,98,0
```
Server đọc từng dòng như sau:
- **`ts`** (Unix giây) là thời điểm đo. Mã bản ghi là `<deviceCode>-<ts>`, nên vòng tay gửi lại log cũ cũng không bị lưu trùng.
- **`t100`** chia 100 ra nhiệt độ da (°C).
- **`hr` / `spo2`** bằng `0` nghĩa là chưa có giá trị.
- **Dòng không có giá trị nào** bị bỏ qua.
- **`conf`** thấp hơn `APP_IOMT_MIN_CONFIDENCE` (mặc định 0 = giữ hết) thì bỏ HR và SpO₂ của dòng đó, vẫn giữ nhiệt độ.
- **`flags`** không được lưu.
- **Giới hạn và lỗi:** mỗi lần gửi tối đa 500 dòng. Log sai định dạng bị từ chối toàn bộ với mã 400 kèm số dòng lỗi; qua MQTT thì nhận ack `FAILED`.

Kết quả trả về giống mục trên.

### Đọc lại số đo (phía phòng khám)
`GET /devices/{id}/readings?limit=50` trả về các số đo mới nhất của một thiết bị, mới nhất trước, tối đa 200 dòng. Cần quyền `devices:read` và `vital_signs:read`. Trang thiết bị trên web có bảng "Số đo gần đây" dùng API này, tự làm mới mỗi 5 giây.

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

**Phát lại log của vòng tay (định dạng `#v2`)**

Mỗi dòng của log gồm các cột sau:

| Cột | Ý nghĩa |
|---|---|
| `ts` | Unix giây |
| `t100` | nhiệt độ da × 100 (3279 = 32,79 °C) |
| `hr`, `spo2` | `0` nghĩa là chưa có giá trị |
| `conf` | độ tin cậy, % |
| `flags` | bit trạng thái; script in ra nhưng không gửi |

```
node tools/device-simulator.mjs --code VC-BAND-01 --token <secret> --csv tools/samples/band-v2.csv --shift now
node tools/device-simulator.mjs --code VC-BAND-01 --token <secret> --csv log.csv --via mqtt --batch 50 --min-conf 80
```

- **Mã bản ghi:** mỗi dòng có mã `<deviceCode>-<ts>`, nên phát lại cùng một file sẽ không lưu trùng.
- **Thời gian đo:** không có `--shift now` thì mỗi dòng giữ nguyên thời gian gốc. Vòng tay phải được giao cho bệnh nhân trước dòng đầu tiên của log, nếu không các dòng sẽ bị từ chối.
- **Giá trị bằng 0** được gửi là trống.
- **`--min-conf`:** dòng có độ tin cậy dưới ngưỡng này bị bỏ HR và SpO₂, nhưng vẫn giữ nhiệt độ.
- **`--raw`:** gửi nguyên văn log (text/csv qua REST, hoặc payload MQTT) để server tự đọc, đúng như vòng tay thật.
- **`--shift N`:** dời log thêm N giây. Dùng cùng một N thì log gửi lại y hệt, server nhận ra trùng; `--shift now` in ra N tương ứng.

**Trên web**
- ADMIN vào trang thiết bị → "Giả lập dữ liệu".
- Hoặc gọi `POST /devices/{id}/simulate` với body `{ "count": 5, "scenario": "LOW_SPO2" }`.
- Các kịch bản: `NORMAL`, `LOW_SPO2`, `HIGH_HEART_RATE`, `FEVER`, `SENSOR_FAULT`.
