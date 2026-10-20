-- V17 treated DISABLED as CLOSED. Administrative blocking is reversible; actual closure isn't.
-- Legacy blocking did not revoke sessions; make restore require a fresh login as new blocking does.
UPDATE login_session s JOIN user_account u ON u.id=s.actor_id
SET s.revoked_at=UTC_TIMESTAMP(6),s.updated_at=UTC_TIMESTAMP(6),s.version=s.version+1
WHERE s.actor_type='CUSTOMER' AND u.status='DISABLED' AND s.revoked_at IS NULL;
UPDATE account_lifecycle l JOIN user_account u ON u.id=l.user_id
SET l.state='ACTIVE'
WHERE u.status='DISABLED' AND l.state='CLOSED'
  AND NOT EXISTS(SELECT 1 FROM account_closure c WHERE c.user_id=u.id);
DROP TRIGGER user_account_lifecycle;
CREATE TRIGGER user_account_lifecycle AFTER INSERT ON user_account FOR EACH ROW
INSERT INTO account_lifecycle(user_id,state)
VALUES(NEW.id,IF(NEW.status='ANONYMIZED','CLOSED','ACTIVE'));

INSERT INTO admin_permission(code,created_at,updated_at)
VALUES('USER_MANAGE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM admin_role r CROSS JOIN admin_permission p
WHERE r.code='SUPER_ADMIN' AND p.code='USER_MANAGE';
