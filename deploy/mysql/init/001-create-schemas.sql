CREATE DATABASE IF NOT EXISTS reagent
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS fake_ops
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'reagent_app'@'%'
  IDENTIFIED BY 'reagent-local-only';
CREATE USER IF NOT EXISTS 'fake_ops_app'@'%'
  IDENTIFIED BY 'fake-ops-local-only';

GRANT ALL PRIVILEGES ON reagent.* TO 'reagent_app'@'%';
GRANT ALL PRIVILEGES ON fake_ops.* TO 'fake_ops_app'@'%';
FLUSH PRIVILEGES;
