-- 指令表新增 temp_config 列，用于 T 类型临时指令的 JSON 参数
ALTER TABLE 指令表 ADD COLUMN temp_config TEXT;
COMMENT ON COLUMN 指令表.temp_config IS '临时指令配置(JSON)，仅指令类型=T时有值';
