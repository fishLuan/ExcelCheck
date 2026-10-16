-- 已有表不重建、不清空。本脚本用于新环境；运行前选择目标数据库。
CREATE TABLE IF NOT EXISTS import_batch (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    file_name VARCHAR(255) NOT NULL,
    file_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    row_count INT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_import_file_sha256 (file_sha256),
    CONSTRAINT chk_import_row_count CHECK (row_count > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS supplier (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    supplier_name VARCHAR(255) NOT NULL,
    credit_code VARCHAR(18) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    contact VARCHAR(100) NOT NULL,
    phone VARCHAR(11) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount DECIMAL(20,2) NOT NULL,
    cooperation_date DATE NOT NULL,
    note TEXT NULL,
    batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_supplier_credit_code (credit_code),
    KEY idx_supplier_batch_id (batch_id),
    CONSTRAINT fk_supplier_batch FOREIGN KEY (batch_id) REFERENCES import_batch(id),
    CONSTRAINT chk_supplier_name CHECK (REGEXP_LIKE(supplier_name,'[^[:space:]]')),
    CONSTRAINT chk_supplier_credit_code CHECK (CHAR_LENGTH(credit_code)=18 AND REGEXP_LIKE(credit_code,'^[0-9A-HJ-NPQRTUWXY]{18}$','c')),
    CONSTRAINT chk_supplier_contact CHECK (REGEXP_LIKE(contact,'[^[:space:]]')),
    CONSTRAINT chk_supplier_phone CHECK (CHAR_LENGTH(phone)=11 AND REGEXP_LIKE(phone,'^1[0-9]{10}$','c')),
    CONSTRAINT chk_supplier_amount CHECK (amount >= 0),
    CONSTRAINT chk_supplier_date CHECK (cooperation_date >= '1000-01-01')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
