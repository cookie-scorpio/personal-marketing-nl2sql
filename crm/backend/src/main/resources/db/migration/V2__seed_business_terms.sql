INSERT INTO business_term(term_code, standard_name, synonyms, definition_text, mapped_object, version_no)
VALUES
    ('HIGH_NET_WORTH', '高净值客户', '高净客群,高净客户,高端客户', '当前总资产不低于100万元或客户等级为PLATINUM的客户。', 'dim_customer.customer_level_code,total_asset_amount', '1.0'),
    ('AUM', '管理资产规模', 'AUM,金融资产,客户资产,资产规模', '客户在本机构持有的存款、理财、基金等资产市值合计。', 'dim_customer.total_asset_amount', '1.0'),
    ('CONVERSION_RATE', '营销转化率', '转化效果,转化占比,成交率', '发生转化的触达客户数除以已触达客户数。', 'fct_customer_marketing.conversion_flag', '1.0'),
    ('CHURN_RISK', '资产下降客户', '流失预警,资产流失,资产下滑', '近三个月资产变动率低于指定负向阈值的客户。', 'dim_customer.asset_change_3m_rate', '1.0'),
    ('WEALTH_PRODUCT', '理财产品', '银行理财,理财持仓,理财客户', '产品分类代码为WEALTH的持有记录。', 'fct_product_holding.product_category_code', '1.0')
ON DUPLICATE KEY UPDATE
    standard_name = VALUES(standard_name),
    synonyms = VALUES(synonyms),
    definition_text = VALUES(definition_text),
    mapped_object = VALUES(mapped_object),
    enabled = TRUE;
