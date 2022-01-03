--RASTEL CRON PROCESSOR v1.0 - 2022-01-02 - Geotemporal correlation procedure
DROP PROCEDURE IF EXISTS rastel_correlate;
DELIMITER //
CREATE PROCEDURE rastel_correlate(
    OUT location_count unsigned bigint(20) = 0
)
BEGIN
    -- Geotemporal correlation table.
    CREATE TEMPORARY TABLE grid_squares(
        cantor_coordinates unsigned bigint(20),
        cantor_quadrant unsigned tinyint(1),
        recorded_date_time timestamp,
        hits unsigned bigint(20)
    )

    -- Load all the unprocessed logs.
    CREATE TEMPORARY TABLE unprocessed_logs
    SELECT * FROM location_recording WHERE correlated_ind = 0

    -- Aggregate logs by location and time.
    INSERT INTO grid_squares (cantor_coordinates, cantor_quadrant, recorded_date_time, hits)
    SELECT cantor_coordinates, cantor_quadrant, recorded_date_time, COUNT(user_id)
    FROM unprocessed_logs
    GROUP BY cantor_coordinates, cantor_quadrant, recorded_date_time

    SELECT location_count = location_count + SUM(hits)
    FROM grid_squares
    WHERE hits > 1

    -- Flag all logs correlated on this basis alone.
    UPDATE location_recording lr SET correlated_ind = 1
    INNER JOIN grid_squares gs
    ON   lr.cantor_coordinates = gs.cantor_coordinates
    AND     lr.cantor_quadrant = gs.cantor_quadrant
    AND     lr.recorded_date_time = gs.recorded_date_time
    AND     gs.hits > 1

    -- These correlations are no longer required.
    DELETE FROM grid_squares WHERE hits > 1

    -- Now correlate these with already processed data.
    UPDATE grid_squares gs SET hits = q.hits + 1
    INNER JOIN (
        SELECT gs2.cantor_coordinates, gs2.cantor_quadrant, gs2.recorded_date_time,
                COUNT(user_id) as hits
        FROM location_recording lr
        INNER JOIN grid_squares gs2
            ON lr.cantor_coordinates = gs2.cantor_coordinates
            AND lr.cantor_quadrant = gs2.cantor_quadrant
            AND lr.recorded_date_time = gs2.recorded_date_time
        GROUP BY gs2.cantor_coordinates, gs2.cantor_quadrant, gs2.recorded_date_time
    ) AS q
    ON gs.cantor_coordinates = q.cantor_coordinates
    AND   gs.cantor_quadrant = q.cantor_quadrant
    AND   gs.recorded_date_time = q.recorded_date_time

    -- Flag all logs correlated on this basis.  At this point, we're done.
    UPDATE location_recording lr SET correlated_ind = 1
    INNER JOIN grid_squares gs
    ON   lr.cantor_coordinates = gs.cantor_coordinates
    AND     lr.cantor_quadrant = gs.cantor_quadrant
    AND     lr.recorded_date_time = gs.recorded_date_time
    AND     gs.hits > 1

    SELECT location_count = location_count + SUM(hits)
    FROM grid_squares
    WHERE hits > 1

    DROP TEMPORARY TABLE grid_squares
    DROP TEMPORARY TABLE unprocessed_logs

END //
DELIMITER ;