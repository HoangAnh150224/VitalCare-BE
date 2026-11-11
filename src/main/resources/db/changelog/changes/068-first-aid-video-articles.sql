--liquibase formatted sql

--changeset vitalcare:068-first-aid-video-articles splitStatements:false
--comment The eight first-aid articles made from the team's videos (047) were left for a doctor's review; the team publishes them as they stand, so every environment shows all thirteen articles, nine with a video. Each not yet on the public page is published with a history step, or added when none is live; one already showing is left alone.

DO $migration$
DECLARE
    src record;
    live record;
    new_id bigint;
BEGIN
    FOR src IN SELECT * FROM (VALUES
  ($fa$Hạ đường huyết$fa$, $fa$FAINTING$fa$, $fa$Run tay, vã mồ hôi, đói cồn cào, lơ mơ ở người tiểu đường hoặc nhịn ăn lâu: cho ăn đường ngay nếu còn tỉnh.$fa$, $fa$## Tình huống
Người bệnh (thường đang dùng thuốc tiểu đường hoặc bỏ bữa) đột ngột run tay, vã mồ hôi, hồi hộp, đói cồn cào, hoa mắt, nói lắp hoặc lơ mơ.

## Việc cần làm ngay
1. Cho người bệnh ngồi hoặc nằm xuống, tránh ngã.
2. Nếu còn tỉnh và nuốt được: cho uống ngay 1 ly nước đường (khoảng 15 g đường), nước trái cây hoặc ngậm 3–4 viên kẹo ngọt.
3. Đợi 15 phút rồi đo lại đường huyết nếu có máy; nếu vẫn thấp hoặc chưa đỡ, cho thêm 15 g đường.
4. Khi đã tỉnh táo, cho ăn một bữa nhẹ có tinh bột (bánh mì, cơm) để đường huyết không tụt lại.

## Những điều không nên làm
- Không cho ăn uống khi người bệnh đã lơ mơ, co giật hoặc bất tỉnh — dễ sặc.
- Không tiêm thêm insulin.
- Không để người bệnh ở một mình hay tự lái xe.

## Khi nào cần hỗ trợ y tế
Người bệnh không tỉnh lại sau 15 phút, ngất, co giật, hoặc hạ đường huyết lặp lại nhiều lần trong ngày.$fa$, $fa$Gọi 115 ngay nếu người bệnh bất tỉnh, co giật hoặc không nuốt được; đặt nằm nghiêng an toàn trong lúc chờ.$fa$, $fa$https://drive.google.com/file/d/1LOF58edVMUoVobUFO2OGj71sIXabU4oP/preview$fa$),
  ($fa$Đột quỵ$fa$, $fa$GENERAL$fa$, $fa$Méo miệng, yếu liệt một bên tay chân, nói khó đột ngột: gọi 115 ngay, ghi lại giờ bắt đầu triệu chứng.$fa$, $fa$## Tình huống
Đột ngột xuất hiện một trong các dấu hiệu BE FAST: mất thăng bằng, nhìn mờ, méo mặt, yếu hoặc tê một bên tay chân, nói ngọng hoặc khó nói.

## Việc cần làm ngay
1. Gọi 115 ngay — thời gian là yếu tố quyết định.
2. Ghi nhớ thời điểm bắt đầu có triệu chứng (hoặc lần cuối thấy người bệnh bình thường).
3. Đặt người bệnh nằm nghỉ, đầu cao khoảng 30 độ; nới lỏng quần áo.
4. Nếu nôn hoặc lơ mơ, đặt nằm nghiêng để tránh sặc.
5. Theo dõi nhịp thở; nếu ngừng thở, bắt đầu ép tim.

## Những điều không nên làm
- Không cho ăn, uống hay uống bất kỳ thuốc nào (kể cả thuốc hạ áp, an cung).
- Không chích máu đầu ngón tay, không cạo gió, không xoa dầu.
- Không tự chở bằng xe máy nếu có thể chờ cấp cứu.

## Khi nào cần hỗ trợ y tế
Luôn luôn — mọi nghi ngờ đột quỵ đều cần đến bệnh viện có khả năng điều trị đột quỵ càng sớm càng tốt.$fa$, $fa$Nghi đột quỵ là gọi 115 ngay, không chờ triệu chứng tự hết; mỗi phút chậm trễ làm mất thêm tế bào não.$fa$, $fa$https://drive.google.com/file/d/1BZu1bcqOvwBhoOuVFGrrQrM5MWSm9Rfa/preview$fa$),
  ($fa$Sốt co giật ở trẻ$fa$, $fa$FEVER$fa$, $fa$Trẻ đang sốt bỗng co giật, mắt trợn, gọi không đáp: đặt nằm nghiêng, không nhét gì vào miệng, theo dõi thời gian.$fa$, $fa$## Tình huống
Trẻ (thường từ 6 tháng đến 5 tuổi) đang sốt thì co giật toàn thân, mắt trợn ngược, gọi không đáp, có thể sùi bọt mép.

## Việc cần làm ngay
1. Giữ bình tĩnh, đặt trẻ nằm nghiêng trên mặt phẳng an toàn, dẹp vật cứng xung quanh.
2. Nới lỏng quần áo, để nơi thoáng khí.
3. Bấm giờ — ghi lại cơn giật kéo dài bao lâu.
4. Sau cơn giật: lau mát bằng nước ấm, cho hạ sốt (paracetamol đúng liều theo cân nặng) khi trẻ đã tỉnh.
5. Đưa trẻ đi khám để tìm nguyên nhân sốt.

## Những điều không nên làm
- Không nhét đũa, thìa, ngón tay hay bất cứ vật gì vào miệng trẻ.
- Không ghì chặt tay chân trẻ.
- Không cho uống thuốc hay nước khi trẻ đang giật hoặc chưa tỉnh.
- Không chườm nước đá hay cồn.

## Khi nào cần hỗ trợ y tế
Cơn giật trên 5 phút, giật lại nhiều lần, trẻ khó thở, tím tái, không tỉnh sau cơn, hoặc trẻ dưới 6 tháng tuổi.$fa$, $fa$Gọi 115 nếu cơn giật kéo dài hơn 5 phút, trẻ tím tái, khó thở hoặc không tỉnh lại sau cơn.$fa$, $fa$https://drive.google.com/file/d/1jMbhPKBuSq4Wexelu44ilLmHMl8uCK8D/preview$fa$),
  ($fa$Gãy xương / Bong gân$fa$, $fa$INJURY$fa$, $fa$Đau chói, sưng, biến dạng sau ngã hoặc va đập: bất động vùng bị thương, chườm lạnh, đưa đi chụp phim.$fa$, $fa$## Tình huống
Sau ngã, va đập hay trật chân: đau nhiều, sưng nề, bầm tím, cử động khó; có thể thấy chi bị biến dạng hoặc lệch trục.

## Việc cần làm ngay
1. Cho người bị thương ngồi hoặc nằm yên, không cố cử động vùng đau.
2. Bất động: dùng nẹp (thanh gỗ, bìa cứng, báo cuộn) cố định qua khớp trên và khớp dưới chỗ đau, lót vải mềm.
3. Chườm lạnh (đá bọc khăn) 15–20 phút để giảm sưng.
4. Kê cao chi bị thương nếu không gây đau thêm.
5. Với vết thương hở: che bằng gạc sạch, không đẩy đầu xương vào trong.

## Những điều không nên làm
- Không nắn, kéo hay bẻ lại chỗ gãy.
- Không xoa bóp, đắp lá, bó thuốc nam.
- Không chườm nóng trong 2 ngày đầu.

## Khi nào cần hỗ trợ y tế
Nghi gãy xương, biến dạng chi, đau không giảm, tê lạnh hoặc tím đầu chi, hoặc chấn thương vùng cổ, lưng, đùi.$fa$, $fa$Nghi chấn thương cột sống (đau cổ, lưng, tê yếu tay chân sau ngã): không di chuyển người bệnh, gọi 115.$fa$, $fa$https://drive.google.com/file/d/1QZdurXup9QYEtE5c7-273S4JXdiqtIOo/preview$fa$),
  ($fa$Đuối nước$fa$, $fa$ENVIRONMENT$fa$, $fa$Người được vớt lên không thở hoặc thở yếu: gọi 115, thổi ngạt 5 lần rồi ép tim — không dốc ngược để xốc nước.$fa$, $fa$## Tình huống
Người bị đuối nước được đưa lên bờ: có thể còn tỉnh, ho sặc sụa; hoặc bất tỉnh, không thở, tím tái.

## Việc cần làm ngay
1. Chỉ cứu khi an toàn cho chính mình — ném phao, sào, dây thay vì nhảy xuống nếu không biết cứu đuối.
2. Gọi 115 hoặc nhờ người gọi.
3. Nếu không thở hoặc thở ngáp: đặt nằm ngửa trên nền cứng, thổi ngạt 5 lần, sau đó ép tim 30 lần xen kẽ thổi ngạt 2 lần cho đến khi có cấp cứu.
4. Nếu còn thở: đặt nằm nghiêng an toàn, cởi đồ ướt, ủ ấm.
5. Mọi người bị đuối nước đều cần đến cơ sở y tế kiểm tra, kể cả khi đã tỉnh.

## Những điều không nên làm
- Không dốc ngược, vác chạy hay ấn bụng để "xốc nước" — làm mất thời gian và gây sặc.
- Không bỏ dở hồi sức khi chưa có nhân viên y tế tiếp nhận.

## Khi nào cần hỗ trợ y tế
Luôn luôn — kể cả khi đã tỉnh, người bị đuối nước vẫn có thể suy hô hấp muộn trong vài giờ sau.$fa$, $fa$Người đuối nước không thở: gọi 115 và hồi sức tim phổi ngay tại chỗ; không mất thời gian xốc nước.$fa$, $fa$https://drive.google.com/file/d/1MpZgCWKrpa4N9SjqCkFtahoeA3JL8u63/preview$fa$),
  ($fa$Tăng huyết áp$fa$, $fa$GENERAL$fa$, $fa$Đau đầu dữ dội, chóng mặt, huyết áp đo được từ 180/110 mmHg: nghỉ ngơi, đo lại, không tự hạ áp quá nhanh.$fa$, $fa$## Tình huống
Người bệnh đau đầu, chóng mặt, nặng gáy, hồi hộp; đo huyết áp thấy rất cao (từ 180/110 mmHg trở lên).

## Việc cần làm ngay
1. Cho người bệnh ngồi nghỉ nơi yên tĩnh, thoáng mát, nới lỏng quần áo.
2. Nghỉ 5–10 phút rồi đo lại huyết áp.
3. Nếu đang có thuốc huyết áp được bác sĩ kê, uống đúng liều thường ngày nếu chưa uống.
4. Ghi lại các số đo và giờ đo để báo bác sĩ.

## Những điều không nên làm
- Không tự ngậm thuốc hạ áp dưới lưỡi hay uống thêm liều — hạ áp quá nhanh có thể gây đột quỵ, nhồi máu.
- Không dùng thuốc của người khác.
- Không gắng sức, không uống rượu bia, cà phê.

## Khi nào cần hỗ trợ y tế
Huyết áp vẫn từ 180/110 mmHg sau khi nghỉ, hoặc kèm đau ngực, khó thở, nhìn mờ, yếu liệt, nói khó, lú lẫn.$fa$, $fa$Huyết áp rất cao kèm đau ngực, khó thở, yếu liệt hoặc nói khó: gọi 115 ngay — đây là cơn tăng huyết áp cấp cứu.$fa$, $fa$https://drive.google.com/file/d/1jX_rj3ZBEbJfKpekQBNehelu6bwiF0GY/preview$fa$),
  ($fa$Ngộ độc thực phẩm$fa$, $fa$GENERAL$fa$, $fa$Nôn, đau bụng, tiêu chảy sau khi ăn: bù nước bằng oresol, giữ lại mẫu thức ăn, theo dõi dấu hiệu mất nước.$fa$, $fa$## Tình huống
Vài giờ sau bữa ăn, một hoặc nhiều người cùng ăn bị buồn nôn, nôn, đau quặn bụng, tiêu chảy, có thể sốt.

## Việc cần làm ngay
1. Ngừng ăn món nghi ngờ; giữ lại mẫu thức ăn, vỏ hộp để cơ quan y tế kiểm tra.
2. Bù nước: uống oresol pha đúng hướng dẫn, uống từng ngụm nhỏ nhiều lần.
3. Cho nghỉ ngơi; khi đỡ nôn thì ăn nhẹ (cháo, súp).
4. Nếu người bệnh nôn khi đang lơ mơ, đặt nằm nghiêng.

## Những điều không nên làm
- Không tự uống thuốc cầm tiêu chảy hay kháng sinh khi chưa có chỉ định.
- Không móc họng gây nôn nếu đã ăn quá lâu hoặc người bệnh lơ mơ.
- Không pha oresol đặc hơn hướng dẫn.

## Khi nào cần hỗ trợ y tế
Nôn hoặc tiêu chảy liên tục không uống được, đi ngoài ra máu, sốt cao, khát nhiều, tiểu ít, li bì; trẻ nhỏ, người già, phụ nữ có thai; nhiều người cùng bị.$fa$, $fa$Ngộ độc kèm khó thở, nhìn mờ, nói khó, yếu cơ hoặc lơ mơ (nghi ngộ độc nặng, độc tố): gọi 115 ngay.$fa$, $fa$https://drive.google.com/file/d/1oXP3iMMEopUyLUR8PPyTU9pJJDkinTqB/preview$fa$),
  ($fa$Hóc dị vật đường thở$fa$, $fa$RESPIRATORY$fa$, $fa$Đang ăn bỗng ôm cổ, không nói, không ho được: vỗ lưng và ép bụng (Heimlich) ngay; trẻ dưới 1 tuổi vỗ lưng ấn ngực.$fa$, $fa$## Tình huống
Đang ăn hoặc ngậm đồ chơi thì đột ngột ôm cổ, ho sặc, thở rít; nặng hơn là không nói, không ho, không thở được, tím tái.

## Việc cần làm ngay
1. Nếu còn ho được: khuyến khích ho mạnh, không can thiệp thêm.
2. Người lớn và trẻ trên 1 tuổi không ho được: đứng sau, cho cúi người, vỗ mạnh 5 lần giữa hai bả vai; nếu chưa ra, ép bụng 5 lần (nghiệm pháp Heimlich). Lặp lại xen kẽ.
3. Trẻ dưới 1 tuổi: đặt nằm sấp dọc cẳng tay, đầu thấp, vỗ lưng 5 lần; lật ngửa, ấn ngực 5 lần bằng 2 ngón tay. Lặp lại.
4. Nếu bất tỉnh: gọi 115, đặt nằm ngửa và bắt đầu ép tim.

## Những điều không nên làm
- Không dùng tay móc mù trong họng — dễ đẩy dị vật vào sâu hơn.
- Không ép bụng trẻ dưới 1 tuổi.
- Không cho uống nước hay nuốt cơm để "trôi" dị vật.

## Khi nào cần hỗ trợ y tế
Sau mọi lần phải ép bụng, hoặc còn ho, khò khè, khó thở kéo dài sau khi dị vật đã ra.$fa$, $fa$Không thở, không ho, tím tái: làm ngay vỗ lưng – ép bụng và gọi 115; nếu bất tỉnh, bắt đầu ép tim.$fa$, $fa$https://drive.google.com/file/d/1mKCdHBsZQD1N8RVarMqSJNsEnq_Y4xn5/preview$fa$)
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
