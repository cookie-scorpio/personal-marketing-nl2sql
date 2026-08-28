-- 仅用于演示库：保留客户编号、姓氏、资产与关联，重新生成虚构完整姓名。
-- 每姓按客户编号排序分配双字名；超过字符池平方后使用三字名，默认1万客户无重名。
UPDATE dim_customer c
JOIN (SELECT customer_id, LEFT(COALESCE(customer_name,customer_name_masked),1) AS surname,
 ROW_NUMBER() OVER(PARTITION BY LEFT(COALESCE(customer_name,customer_name_masked),1) ORDER BY customer_id)-1 AS seq
 FROM dim_customer) n ON n.customer_id=c.customer_id
SET c.customer_name=CONCAT(n.surname,
 IF(n.seq>=13924,SUBSTRING('安柏博辰承初楚淳达丹冬恩帆芳飞枫歌涵航和衡恒宏泓华嘉佳江锦靖景静君俊凯康岚朗乐礼林霖凌明铭沐宁诺佩平启清秋然仁荣瑞若山杉尚诗书思松苏棠天庭彤桐宛维文闻溪希夏贤向晓心欣新星修轩雪雅言彦阳尧一依宜亦逸奕音盈颖瑜雨语宇羽远悦云泽知致中舟竹卓梓',1+MOD(FLOOR(n.seq/13924)-1,118),1),''),
 SUBSTRING('安柏博辰承初楚淳达丹冬恩帆芳飞枫歌涵航和衡恒宏泓华嘉佳江锦靖景静君俊凯康岚朗乐礼林霖凌明铭沐宁诺佩平启清秋然仁荣瑞若山杉尚诗书思松苏棠天庭彤桐宛维文闻溪希夏贤向晓心欣新星修轩雪雅言彦阳尧一依宜亦逸奕音盈颖瑜雨语宇羽远悦云泽知致中舟竹卓梓',1+MOD(FLOOR(n.seq/118),118),1),
 SUBSTRING('安柏博辰承初楚淳达丹冬恩帆芳飞枫歌涵航和衡恒宏泓华嘉佳江锦靖景静君俊凯康岚朗乐礼林霖凌明铭沐宁诺佩平启清秋然仁荣瑞若山杉尚诗书思松苏棠天庭彤桐宛维文闻溪希夏贤向晓心欣新星修轩雪雅言彦阳尧一依宜亦逸奕音盈颖瑜雨语宇羽远悦云泽知致中舟竹卓梓',1+MOD(n.seq,118),1));
UPDATE dim_customer SET customer_name_masked=CONCAT(LEFT(customer_name,1),REPEAT('*',CHAR_LENGTH(customer_name)-1));
