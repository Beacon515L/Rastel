--RASTEL CRON PROCESSOR v1.0 - 2022-01-02 - Email notification procedure
DROP PROCEDURE IF EXISTS rastel_notify;
DELIMITER //
CREATE PROCEDURE rastel_notify()
BEGIN
    CREATE TEMPORARY TABLE user_notification (
        user_id unsigned bigint(20) PRIMARY KEY,
        latest_test_date TIMESTAMP NULL,
        leaving_isolation TIMESTAMP NULL
    )

    -- Identify all users with positive contact dates.
    INSERT INTO user_notification (user_id)
    SELECT DISTINCT user_id
    FROM location_recording
    WHERE correlated_ind = 2

    -- Of these, identify those in isolation.
    -- For this we need their latest test.
    UPDATE user_notification un
    SET latest_test_date = q.latest_test_date
    INNER JOIN (
        SELECT user_id, MAX(time_taken) AS latest_test_date
        FROM user_test
        GROUP BY user_id
    ) AS q
    ON un.user_id = q.user_id

    UPDATE user_notification un
    SET leaving_isolation = time_departing_isolation
    INNER JOIN user_test ut
    ON un.user_id = ut.user_id AND un.latest_test_date = ut.time_taken

    SELECT un.user_id, email, timezone, leaving_isolation
    FROM user_notification un
    INNER JOIN user u ON un.user_id = u.user_id
    WHERE leaving_isolation

END //
DELIMITER ;