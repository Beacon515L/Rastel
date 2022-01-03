--RASTEL CRON PROCESSOR v1.0 - 2022-01-02 - Geotemporal correlation procedure
DROP PROCEDURE IF EXISTS rastel_flag;
DELIMITER //
CREATE PROCEDURE rastel_flag(
    IN time_resolution unsigned unsigned int,
    IN close_contact_time_min unsigned int,
    IN close_contact_time_max unsigned int,
    IN infectious_period unsigned int,
    OUT location_count unsigned bigint(20) = 0
)
BEGIN
    -- Load all the correlated, flagged and notified logs.
    CREATE TEMPORARY TABLE correlated_logs
    SELECT * FROM location_recording WHERE correlated_ind > 0

    -- Load all the tests of users who were present in these logs.
    CREATE TEMPORARY TABLE correlated_user_tests
    SELECT ut.user_id, positive_test, time_taken, time_departing_isolation
    FROM user_test ut
    INNER JOIN (
        SELECT DISTINCT user_id FROM correlated_logs
    ) AS q ON ut.user_id = q.user_id

    -- Determine windows of positivity per user.  For this, we first identify "positivity events",
    -- i.e. times at which the positivity status of a user may have changed.
    -- These will be when tests are taken, and when isolation is intended to be departed.
    CREATE TABLE positivity_event (
    user_id unsigned bigint(20),
    sequence_number unsigned bigint(20) NULL,
    preceding_positive_id unsigned bigint(20) NULL,
    succeeding_negative_id unsigned bigint(20) NULL,
    event_time TIMESTAMP,
    positive unsigned tinyint(1) -- This algorithm turns on assigning values to positive.
                        -- - 0 or 1: Respectively negative or positive, absolutely. From direct test results.
                        -- - 2: Assumed negative due to passage of time. Overridden by subsequent tests.
                        -- - 3: Assumed negative invalidated by analysis.
                        -- - 4: Assumed negative confirmed by analysis.
                        -- - 5: Positive with identified endpoint.
    )

    -- First, every test is theoretically a positivity event in the direction of its result.
    INSERT INTO positivity_event (user_id, event_time, positive)
    SELECT user_id, time_taken, positive_test
    FROM correlated_user_tests

    -- Secondly, every nominal departure from quarantine is a positivity event.
    -- However, where tests are absolute, nominal departures are only indicative and will be overridden
    -- by subsequent tests.
    INSERT INTO positivity_event (user_id, event_time, positive)
    SELECT user_id, time_departing_isolation, 2
    FROM correlated_user_tests

    -- Thirdly, every INFECTIOUS_PERIOD later than a positive test is taken is a positivity event.
    -- Again indicative only.
    INSERT INTO positivity_event (user_id, event_time, positive)
    SELECT user_id, time_taken + infectious_period, 2
    FROM correlated_user_tests
    WHERE positive_test = 1

    -- We now need to remove the indicative positivity events that are contradicted by positive tests.
    -- An indicative negative event is valid so long as the nearest preceding positive test does not
    -- nominate a longer time until the end of quarantine (whether explicitly or via INFECTIOUS_PERIOD).
    -- To do this we first need to order the events with a sequence number.
    UPDATE positivity_event pe
    SET sequence_number = q.sequence_number,
        preceding_positive_id = q.sequence_number
        succeeding_negative_id = q.sequence_number
    INNER JOIN (
        SELECT user_id, event_time, positive,
        ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY event_time asc) AS sequence_number
        FROM positivity_event
    ) AS q
    ON      pe.user_id = q.user_id
    AND     pe.event_time = q.event_time
    AND     pe.positive = q.positive

    -- We now step backwards through each positivity event's history until each one lines up with
    -- its immediately preceding event.
    -- If that event is a test, we stop iterating and mark the event as confirmed if the
    -- test's quarantine departure date precedes or is the positivity event, and reject it otherwise.
    -- If that event is another assumed negative, keep iterating.
    -- If iteration runs to the start of the list, mark the event as confirmed.
    WHILE (SELECT COUNT(1) FROM positivity_event WHERE positive = 2)
    BEGIN
        -- Decrement everything.
        UPDATE positivity_event
        SET preceding_positive_id = preceding_positive_id - 1
        WHERE positive = 2

        -- Immediately confirm anything that now has no preceding events.
        UPDATE positivity_event
        SET positive = 3
        WHERE preceding_positive_id = 0
        AND positive = 2

        -- Confirm/reject all other events where the event now identified is a test.
        UPDATE positivity_event pe
        SET positive = CASE WHEN q.time_departing_isolation <= event_time THEN 4 ELSE 3 END
        INNER JOIN (
            SELECT pe2.user_id, sequence_number,
            CASE WHEN time_departing_isolation IS NULL THEN cet.time_taken + infectious_period
             ELSE time_departing_isolation END AS time_departing_isolation
            FROM positivity_event pe2
            INNER JOIN correlated_user_tests cet
                ON pe2.user_id = cet.user_id
                AND pe2.event_time = cet.time_taken
        ) AS q
        ON  q.user_id = pe.user_id
        AND q.sequence_number = pe.sequence_number
        AND positive = 2
    END

    -- Delete all rejected events.
    DELETE FROM positivity_event WHERE positive = 3

    -- We use a similar technique in the other direction to determine when each positive test goes
    -- negative.  Before we begin, however, we need to identify the highest index for each user.
    CREATE TEMPORARY TABLE positivity_event_maxima
    SELECT user_id, MAX(sequence_number) AS max_sequence_number
    FROM positivity_event
    GROUP BY user_id

    WHILE (SELECT COUNT(1) FROM positivity_event WHERE positive = 1)
    BEGIN
        -- Increment everything.
        UPDATE positivity_event
        SET preceding_positive_id = preceding_positive_id + 1
        WHERE positive = 1

        -- Pull anything that now has no succeeding events out of the loop. These will have
        -- to be inferred as INFECTIOUS_PERIOD (because these people are currently positive).
        -- Theoretically this shouldn't happen.
        UPDATE positivity_event pe
        SET positive = 5
        FROM positivity_event_maxima pem
        WHERE succeeding_negative_id > max_sequence_number
        AND positive = 1

        -- Otherwise, pull positive events out of the loop ONLY when both of the following are true
        -- about succeeding_negative_id:
        -- - A positivity_event record exists for this user and that value of succeeding_negative_id.
        -- - That record is a negative event.
        UPDATE positivity_event pe
        SET positive = 5
        INNER JOIN (
            SELECT user_id, sequence_number, positive
            FROM positivity_event pe2
            INNER JOIN positivity_event pe3
                ON pe2.user_id = pe3.user_id
                AND pe2.succeeding_negative_id = pe3.sequence_number
                AND pe3.positive IN (0,4)
        ) as q
        ON  pe.user_id = pe2.user_id
        AND pe.sequence_number = pe2.sequence_number
        AND pe.positive = 1
    END

    -- Now construct a map of positivity periods.
    CREATE TABLE positivity_periods (
        user_id unsigned bigint(20),
        start_date TIMESTAMP,
        end_date TIMESTAMP NULL
    )

    -- These run from each positive test to the next negative event (test or inferred).
    INSERT INTO positivity_periods (user_id, start_date, end_date)
    SELECT pe.user_id, pe.event_date,
    CASE pe2.event_date IS NULL THEN pe.event_date + infectious_period ELSE pe2.event_date END
    FROM positivity_event pe
    LEFT JOIN positivity_event pe2
        ON pe.user_id = pe2.user_id
        AND pe.succeeding_negative_id = pe2.sequence_number

    -- We now have tables of positive periods for each user.
    -- We flag every log belonging to these users for these periods directly.
    UPDATE correlated_logs cl
    INNER JOIN positivity_periods pp
        ON cl.user_id = pp.user_id
        AND cl.recorded_date_time >= pp.start_date
        AND cl.recorded_date_time <= pp.end_date
        AND correlated_ind = 1
    SET correlated_ind = 2

    -- Now TEMPORARILY flag every correlated log directly spatiotemporally coincident with  either
    -- these logs or logs already flagged.
    UPDATE correlated_logs cl
    INNER JOIN correlated_logs cl2
        ON  cl.correlated_ind = 1
        AND cl2.correlated_ind > 1
        AND cl.cantor_coordinates = cl2.cantor_coordinates
        AND cl.cantor_quadrant = cl2.cantor_quadrant
        AND cl.recorded_date_time = cl2.recorded_date_time
    SET correlated_ind = 99 -- Magic value meaning correlated before close contact time factored

    -- Now we analyse for close contact time.
    -- A log is confirmed as flagged if it sits within a window CLOSE_CONTACT_TIME_MAX seconds wide
    -- centred on itself (i.e. half of CLOSE_CONTACT_TIME_MAX precedes it and half succeeds it) in
    -- which at least CLOSE_CONTACT_TIME_MIN seconds worth of logs are spatiotemporally correlated.
    -- Otherwise it is unflagged.
    -- This is done by chunking and literally counting up each correlation.
    -- First, we need to add reference columns to correlated_logs.
    ALTER TEMPORARY TABLE correlated_logs ADD reference_time TIMESTAMP null;
    ALTER TEMPORARY TABLE correlated_logs ADD correlation_count unsigned bigint(20) DEFAULT 1;

    -- Initialize reference_time to half of CLOSE_CONTACT_TIME_MAX ago, normalized to TIME_RESOLUTION.
    UPDATE correlated_logs
    SET reference_time =
        floor((recorded_date_time - close_contact_time_max / 2) / time_resolution) * time_resolution
    WHERE correlated_ind = 99

    -- Iterate in windows of TIME_RESOLUTION, counting up how many correlated hits occur per log.
    -- Any log that reaches CLOSE_CONTACT_TIME_MIN hits before CLOSE_CONTACT_TIME_MAX iterations
    -- is flagged, otherwise it remains merely correlated.
    -- The required number of hits is the required number of TIME_RESOLUTION intervals that fit into
    -- CLOSE_CONTACT_TIME_MIN. I am choosing to round down rather than up to err on the side of caution.
    DECLARE required_hits unsigned bigint(20) = floor(close_contact_time_min / time_resolution)

    WHILE (SELECT COUNT(1) FROM correlated_logs WHERE correlated_ind = 99)
    BEGIN
        -- Check for hits
        UPDATE correlated_logs cl
        INNER JOIN correlated_logs cl2
            ON cl.correlated_ind = 99
            AND cl2.correlated_ind = 99
            AND cl.user_id = cl2.user_id
            AND cl.recorded_date_time = cl2.reference_time
        SET correlation_count = cl.correlation_count + 1;

        -- If any records have now met the required hit threshold, confirm and stop iterating them.
        -- At the same time, advance reference_time.
        UPDATE correlated_logs
        SET correlated_ind = CASE correlation_count >= required_hits
            THEN 22 -- Magic value which means we need to do an update.
            ELSE 99 END,
        SET reference_time = reference_time + time_resolution
        WHERE correlated_ind = 99

        -- If we have reached CLOSE_CONTACT_TIME_MAX, flag every log not by now meeting the required
        -- hit count as not flagged.
        UPDATE correlated_logs
        SET correlated_ind = 1
        WHERE correlated_ind = 99
        AND   reference_time >= recorded_date_time + close_contact_time_max / 2
    END

    -- The only updates we need to actually run are updating unflagged logs to flagged where required.
    UPDATE location_recording lr
    INNER JOIN correlated_logs cl
        ON lr.user_id = cl.user_id
        AND lr.recorded_date_time = cl.recorded_date_time
        AND cl.correlated_ind = 22
    SET lr.correlated_ind = 2

    SELECT location_count = COUNT(1) FROM correlated_logs WHERE correlated_ind = 22

    DROP TEMPORARY TABLE correlated_logs
    DROP TEMPORARY TABLE positivity_periods
    DROP TEMPORARY TABLE correlated_user_tests
    DROP TEMPORARY TABLE positivity_event

END //
DELIMITER ;