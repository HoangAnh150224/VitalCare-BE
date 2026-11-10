--liquibase formatted sql

--changeset vitalcare:067-first-aid-visible splitStatements:false
--comment 066 published again only an article whose status was not PUBLISHED; one marked PUBLISHED whose published revision has no PUBLISHED history step (what the public page reads) stayed hidden. Asked of what the public page shows instead: each of the team's published articles that no live article by its title shows is published again with a history step, or added when none is live. An article already showing is left alone.

DO $migration$
DECLARE
    src record;
    live record;
    new_id bigint;
BEGIN
    FOR src IN SELECT * FROM (VALUES
  ($fa$Khó thở$fa$, $fa$RESPIRATORY$fa$, $fa$Cách xử trí ban đầu và khi nào cần trợ giúp.$fa$, $fa$## Tình huống
Người bệnh thở nhanh, thở rít, phải ngồi dậy mới thở được hoặc nói không thành câu.

## Việc cần làm ngay
1. Giữ bình tĩnh, cho người bệnh ngồi thẳng, hơi cúi về phía trước.
2. Nới lỏng quần áo ở cổ, ngực và thắt lưng.
3. Mở cửa cho thoáng khí, tránh khói và mùi nồng.
4. Nếu người bệnh có thuốc xịt hen do bác sĩ kê, giúp họ dùng đúng liều.
5. Theo dõi nhịp thở, màu da và mức tỉnh táo cho tới khi đỡ hơn hoặc có người hỗ trợ.

## Những điều không nên làm
- Không cho ăn uống khi đang khó thở.
- Không bắt người bệnh nằm thẳng.
- Không tự ý dùng thuốc của người khác.

## Khi nào cần hỗ trợ y tế
Gọi 115 ngay nếu môi hoặc đầu ngón tay tím tái, người bệnh lơ mơ, hoặc khó thở không đỡ sau vài phút.$fa$, $fa$Môi tím, lơ mơ hoặc không nói được thành câu: gọi 115 ngay.$fa$, NULL),
  ($fa$Ngất xỉu$fa$, $fa$FAINTING$fa$, $fa$Cách giúp người bị ngất.$fa$, $fa$## Tình huống
Người bệnh đột ngột mất ý thức trong thời gian ngắn, thường kèm chóng mặt, vã mồ hôi, da tái trước đó.

## Việc cần làm ngay
1. Đỡ người bệnh nằm ngửa ở nơi an toàn, thoáng mát.
2. Kê cao hai chân khoảng 30 cm nếu không có chấn thương.
3. Nới lỏng quần áo chật.
4. Kiểm tra hơi thở. Nếu người bệnh nôn, đặt nằm nghiêng an toàn.
5. Khi tỉnh lại, cho nằm nghỉ thêm vài phút rồi mới ngồi dậy từ từ.

## Những điều không nên làm
- Không tạt nước hay lay mạnh người bệnh.
- Không cho ăn uống khi chưa tỉnh hẳn.
- Không để người bệnh đứng dậy ngay.

## Khi nào cần hỗ trợ y tế
Gọi 115 nếu người bệnh không tỉnh sau một phút, không thở, ngất kèm đau ngực, co giật, hoặc ngất khi đang gắng sức.$fa$, $fa$Không tỉnh sau 1 phút hoặc không thở: gọi 115 và bắt đầu hồi sức nếu đã được hướng dẫn.$fa$, NULL),
  ($fa$Sốt cao$fa$, $fa$FEVER$fa$, $fa$Hạ sốt an toàn tại nhà và dấu hiệu cần đi khám.$fa$, $fa$## Tình huống
Nhiệt độ đo ở nách từ 38,5°C trở lên, người bệnh mệt, nóng bừng, có thể rét run.

## Việc cần làm ngay
1. Cho người bệnh mặc đồ mỏng, nằm nơi thoáng mát.
2. Lau người bằng khăn ấm ở trán, nách, bẹn.
3. Cho uống nhiều nước, nước oresol pha đúng hướng dẫn.
4. Dùng thuốc hạ sốt paracetamol đúng liều theo cân nặng nếu đã được bác sĩ dặn.
5. Đo lại nhiệt độ sau 30 đến 60 phút.

## Những điều không nên làm
- Không đắp chăn kín hay ủ ấm.
- Không lau bằng nước đá hoặc cồn.
- Không dùng aspirin cho trẻ em.

## Khi nào cần hỗ trợ y tế
Đi khám ngay nếu sốt trên 39,5°C, sốt kèm co giật, cứng cổ, phát ban, li bì, hoặc trẻ dưới 3 tháng tuổi bị sốt.$fa$, $fa$Sốt kèm co giật, cứng cổ hoặc li bì: gọi 115 hoặc đến cơ sở y tế gần nhất.$fa$, NULL),
  ($fa$Vết thương chảy máu$fa$, $fa$WOUND$fa$, $fa$Cầm máu và chăm sóc vết thương cơ bản.$fa$, $fa$## Tình huống
Vết cắt, vết rách da đang chảy máu.

## Việc cần làm ngay
1. Rửa tay hoặc đeo găng nếu có.
2. Dùng gạc hoặc khăn sạch ép chặt trực tiếp lên vết thương ít nhất 10 phút.
3. Nâng phần bị thương cao hơn tim nếu được.
4. Khi máu ngừng, rửa nhẹ bằng nước sạch hoặc nước muối sinh lý.
5. Băng lại bằng gạc vô trùng và thay băng hằng ngày.

## Những điều không nên làm
- Không rắc thuốc lá, bột hay đắp lá lên vết thương.
- Không rút dị vật cắm sâu ra khỏi vết thương.
- Không mở băng liên tục để kiểm tra khi máu chưa ngừng.

## Khi nào cần hỗ trợ y tế
Đi khám nếu máu không ngừng sau 10 phút ép, vết thương sâu, há miệng, do vật bẩn hoặc động vật cắn, hoặc có dấu hiệu nhiễm trùng.$fa$, $fa$Máu phun thành tia hoặc không cầm được: tiếp tục ép chặt và gọi 115.$fa$, NULL),
  ($fa$Bỏng nhẹ$fa$, $fa$INJURY$fa$, $fa$Làm mát vết bỏng đúng cách.$fa$, $fa$## Tình huống
Bỏng do nước sôi, hơi nóng hoặc vật nóng, vùng da đỏ, rát, có thể phồng rộp nhỏ.

## Việc cần làm ngay
1. Đưa người bệnh ra khỏi nguồn nhiệt.
2. Xả vùng bỏng dưới vòi nước mát (không lạnh buốt) trong 20 phút.
3. Tháo nhẫn, đồng hồ, quần áo quanh vùng bỏng trước khi sưng.
4. Che phủ nhẹ bằng gạc sạch hoặc màng bọc thực phẩm.

## Những điều không nên làm
- Không bôi kem đánh răng, nước mắm, dầu hay đá lạnh.
- Không chọc vỡ bóng nước.
- Không bóc phần quần áo dính vào vết bỏng.

## Khi nào cần hỗ trợ y tế
Đi khám nếu vết bỏng rộng hơn lòng bàn tay, ở mặt, tay, bộ phận sinh dục, khớp, bỏng do hoá chất hoặc điện, hoặc người bị bỏng là trẻ nhỏ, người cao tuổi.$fa$, $fa$Bỏng do điện, hoá chất hoặc bỏng rộng: gọi 115.$fa$, $fa$https://drive.google.com/file/d/152m6ev_k18SLT62YdEDeQssjeWGp38-1/preview$fa$)
    ) AS s(title, category, summary, body, warning, video)
    LOOP
        -- Already on the public page: nothing to do.
        CONTINUE WHEN EXISTS (
            SELECT 1 FROM first_aid_article a JOIN first_aid_article_history h ON h.article_id = a.id
            WHERE a.title = src.title AND a.deleted_at IS NULL AND a.published_revision IS NOT NULL
              AND h.revision = a.published_revision AND h.to_status = 'PUBLISHED');

        SELECT * INTO live FROM first_aid_article
        WHERE title = src.title AND deleted_at IS NULL
        ORDER BY id DESC LIMIT 1;

        IF NOT FOUND THEN
            INSERT INTO first_aid_article (title, category, summary, body, emergency_warning, video_url, status, revision,
                                           published_revision, published_at, created_at, version)
            VALUES (src.title, src.category, src.summary, src.body, src.warning, src.video, 'PUBLISHED', 1, 1, now(), now(), 0)
            RETURNING id INTO new_id;
            INSERT INTO first_aid_article_history (article_id, revision, from_status, to_status, note, at,
                                                   title, category, summary, body, emergency_warning, video_url)
            VALUES (new_id, 1, NULL, 'PUBLISHED', 'Nội dung sơ cứu của nhóm', now(),
                    src.title, src.category, src.summary, src.body, src.warning, src.video);
        ELSE
            UPDATE first_aid_article
            SET category = src.category, summary = src.summary, body = src.body, emergency_warning = src.warning,
                video_url = src.video, status = 'PUBLISHED', revision = live.revision + 1,
                published_revision = live.revision + 1, published_at = now(), updated_at = now(),
                version = live.version + 1
            WHERE id = live.id;
            INSERT INTO first_aid_article_history (article_id, revision, from_status, to_status, note, at,
                                                   title, category, summary, body, emergency_warning, video_url)
            VALUES (live.id, live.revision + 1, live.status, 'PUBLISHED', 'Xuất bản lại nội dung sơ cứu của nhóm', now(),
                    src.title, src.category, src.summary, src.body, src.warning, src.video);
        END IF;
    END LOOP;
END
$migration$;

--rollback -- Left in place: the articles may have been edited or reviewed by then.
