--liquibase formatted sql

--changeset vitalcare:062-role-names-vi
--comment The roles are named and described in Vietnamese, as every screen that shows them is; the codes stay as they are.

UPDATE roles SET name = 'Quản trị hệ thống',
    description = 'Tài khoản duy nhất, khởi tạo khi triển khai. Tạo cơ sở và Quản trị phòng khám, quản lý cấp hệ thống.'
WHERE code = 'ADMIN';

UPDATE roles SET name = 'Quản trị phòng khám',
    description = 'Do Quản trị hệ thống tạo. Quản lý cơ sở được giao: tạo Bác sĩ và Lễ tân, thiết bị, lịch khám.'
WHERE code = 'CLINIC_ADMIN';

UPDATE roles SET name = 'Bác sĩ',
    description = 'Do Quản trị phòng khám tạo. Khám bệnh, ghi kết quả, chỉ định dịch vụ và theo dõi IoMT bệnh nhân được giao.'
WHERE code = 'DOCTOR';

UPDATE roles SET name = 'Nhân viên y tế (Lễ tân)',
    description = 'Do Quản trị phòng khám tạo. Tiếp nhận walk-in, tạo hồ sơ bệnh nhân, check-in, thu phí khám và quản lý hàng đợi.'
WHERE code = 'MANAGER';

UPDATE roles SET name = 'Khách',
    description = 'Tự đăng ký. Xem phòng khám, đặt lịch khám; dùng chức năng Người chăm sóc khi được bệnh nhân chia sẻ.'
WHERE code = 'GUEST';

UPDATE roles SET name = 'Bệnh nhân',
    description = 'Khách đã check-in hợp lệ, hoặc hồ sơ walk-in đã liên kết tài khoản. Xem kết quả khám, chỉ số và chia sẻ cho người chăm sóc.'
WHERE code = 'PATIENT';

--rollback -- Names are display text only; not restored.
