-- Isolated integration-test accounts only. Their encrypted password is copied
-- from the local seed admin without ever printing the stored hash.
INSERT INTO iam_user_info (
    id, name, client_code, account, password, administrator, status, deleted,
    create_time, last_modified_time
)
SELECT 9000000000000000201, 'PayLens No Permission Test', 'admin', 'paylens_no_permission',
       password, false, 'normal', false, now(), now()
FROM iam_user_info
WHERE account = 'bootx' AND client_code = 'admin'
LIMIT 1;

INSERT INTO iam_user_info (
    id, name, client_code, account, password, administrator, status, deleted,
    create_time, last_modified_time
)
SELECT 9000000000000000202, 'PayLens View Only Test', 'admin', 'paylens_view_only',
       password, false, 'normal', false, now(), now()
FROM iam_user_info
WHERE account = 'bootx' AND client_code = 'admin'
LIMIT 1;

INSERT INTO iam_role (
    id, code, client_code, internal, deleted, create_time, last_modified_time
)
VALUES (9000000000000000101, 'paylens_test_view_only', 'admin', false, false, now(), now());

INSERT INTO iam_role_code (id, role_id, code_id)
SELECT 9000000000000000301, 9000000000000000101, id
FROM iam_perm_code
WHERE code = 'trade:abnormal-order:view' AND deleted = false
LIMIT 1;

INSERT INTO iam_role_menu (id, role_id, client_code, menu_id)
SELECT 9000000000000000401, 9000000000000000101, 'admin', id
FROM iam_perm_menu
WHERE menu_code = 'trade:abnormal-order' AND client_code = 'admin' AND deleted = false
LIMIT 1;

INSERT INTO iam_user_role (id, user_id, role_id)
SELECT 9000000000000000501, id, 9000000000000000101
FROM iam_user_info
WHERE account = 'paylens_view_only' AND client_code = 'admin'
LIMIT 1;
