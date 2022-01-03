<?php
    VERSION = "v1.0 - 2022-01-02";
    //General batch process handler.  Set in a crontab every five minutes.
    require_once("config.php");
    log("RASTEL CRON PROCESSOR ".VERSION." - STARTING");
    $db = new PDO(DATABASE_CONNECTION_STRING,DATABASE_USER,DATABASE_PASSWORD);
    if(!$db){
        log("Database connection failed. Aborting.");
        exit(-1);
    }
    log("Database connection established");

    $time = time();
    log("Reference time for this batch: ".date('c',$time));

    //The general order of processing:
    //1. Delete logs older than INFECTIOUS_PERIOD
    //2. Correlate spatiotemporally coincident logs from unprocessed logs
    //3. Flag logs where positive contact exists from correlated logs
    //4. Dispatch notifications for flagged logs

    //Processing should always occur, unless the latest date of a correlated log is less than
    //PROCESSING_DELAY ago.

    $processingCheckResult = $db->query("SELECT recorded_date_time FROM location_recording "
    ."WHERE correlated_ind > 1 ORDER BY recorded_date_time DESC LIMIT 1");

    $processingCheckResults = $processingCheckResult->fetchAll(PDO::FETCH_ASSOC);

    //If this condition fails, a single date wasn't obtainable.  In this case always run.
    if(count($processingCheckResults == 1)){
        //Otherwise, if the date is less than PROCESSING_DELAY ago, quit successfully.
        $deltaProcessingCheckResult = $time - $processingCheckResults[0]["recorded_date_time"];
        log("Last processed log dated ".$deltaProcessingCheckResult." seconds ago");
        if($deltaProcessingCheckResult < PROCESSING_DELAY){
            log("PROCESSING_DELAY = ".PROCESSING_DELAY.", nothing to do");
            exit(0);
        }
    }
    log("STEP 1 - DELETE LOGS OLDER THAN INFECTIOUS_PERIOD (".INFECTIOUS_PERIOD." seconds ago)");

    //This doesn't require a transaction. Anything this old should be unconditionally pruned.
    $logDeletionResult = $db->exec("DELETE FROM location_recording WHERE recorded_date_time < "
    $time - INFECTIOUS_PERIOD);

    if(!$logDeletionResult){
        log("Log deletion failed, aborting!");
        exit(-2);
    }

    log("Log deletion completed; ".$logDeletionResult->rowCount()." records purged.");

    log("STEP 2 - GEOTEMPORALLY CORRELATE LOGS");
    //Run the correlation stored procedure.
    $rastelCorrelateResult = $db->exec("CALL rastel_correlate(@location_count)");
    if(!$rastelCorrelationResult){
            log("Log correlation failed, aborting!");
            exit(-3);
        }
    $rastelCorrelateCount = $db->query("SELECT @location_count");

    //This failing isn't fatal, it is informational only. We can play a bit fast and loose with it.
    //I suspect there is a less janky way of doing this, but meh.
    if($rastelCorrelateCount){
        $rastelCorrelateCounts = $rastelCorrelateCount->fetchAll(PDO::FETCH_NUM);
        log("Total records correlated: ".$rastelCorrelateCounts[0][0]);
    }

    log("STEP 3 - FLAG POSITIVE CONTACT");
    //Run the flagging stored procedure.
        $rastelFlagResult = $db->exec("CALL rastel_flag(@location_count)");
        if(!$rastelFlagResult){
                log("Log flagging failed, aborting!");
                exit(-4);
            }
        $rastelFlagCount = $db->query("SELECT @location_count");

        //This failing isn't fatal, it is informational only. We can play a bit fast and loose with it.
        //I suspect there is a less janky way of doing this, but meh.
        if($rastelFlagCount){
            $rastelFlagCounts = $rastelFlagCount->fetchAll(PDO::FETCH_NUM);
            log("Total records flagged: ".$rastelFlagCounts[0][0]);
        }

    log("STEP 4 - DISPATCH EMAILS");
    //This is done sequentially.  For each user, obtain the latest log that is more than correlated
    //(i.e. either flagged positive, or previously notified).
    //If the user's last such log is flagged positive and not notified, AND the user does not have
    //a positive test of their own that is more recent than this log, notify them.
    //Run the correlation stored procedure.
        $rastelNotifyResult = $db->exec("CALL rastel_notify()");
        if(!$rastelNotifyResult){
                log("Log notification retrieval failed, aborting!");
                exit(-5);
            }

        $rastelNotifyResults = $rastelNotifyResult->fetchAll(PDO::FETCH_ASSOC);
        $emailAttempts = 0; $emailSuccesses = 0;

        foreach ($rastelNotifyResults as $notification){
            //If the user is presently in quarantine (leaving_quarantine is not null and in the
            //future), notify them, otherwise don't.
            if($notification["leaving_quarantine"] !== null
                && $time < $notification["leaving_quarantine"]){
                continue;
            }

            $db->startTransaction();
            //Retrieve every flagged, non-notified log for this user.
            $notificationLogResult = $db->query(
            "SELECT recorded_date_time FROM location_recording WHERE user_id = "
            .$notification["user_id"]." AND correlated_ind = 2";
            );

            if(!$notificationLogResult){
                    log("Log retrieval for notifications failed, aborting!");
                    exit(-6);
                }

            $notificationLogResults = $notificationLogResult->fetchAll(PDO::FETCH_ASSOC);
            $notificationLogs = [];

            //Invert the array to use array_pop() from the start
            $notificationLogResults = array_reverse($notificationLogResults);

            $lastNLogResult = array_pop($notificationLogResults);
            $currentLogTable = [
                $lastNLogResult["result_date_time"],
                $lastNLogResult["result_date_time"] + TIME_RESOLUTION);

            while ($lastNLogResult !== null){
                    $lastNLogResult = array_pop($notificationLogResults);
                //If this log is more than 2 * TIME_RESOLUTION ahead of the current period's
                //end time, it is a discontinuity. Commit this period and start the next.
                if($lastNLogResult["result_date_time"] - $currentLogTable[1] < 2 * TIME_RESOLUTION){
                    $notificationLogs[] = $currentLogTable;
                    $currentLogTable = [
                        $lastNLogResult["result_date_time"],
                        null];
                }
                //Set the end time of the current period to this log plus TIME_RESOLUTION.
                $currentLogTable[1] = $lastNLogResult["result_date_time"] + TIME_RESOLUTION;
            }
            $notificationLogs[] = $currentLogTable;

            $emailAttempts++;
            //Send an email for these logs
            $to = $notification["email"];
            $subject = EMAIL_SUBJECT;
            $message = EMAIL_BODY_PRE."<ul>";
            $default_timezone = date_default_timezone_get();
            date_default_timezone_set($notification["timezone"]);
                foreach ($notificationLogs as $notificationLog){
                    $message .= "<li>".date(DATE_FORMAT,$notificationLog[0])
                        ." to ".date(DATE_FORMAT,$notificationLog[1])."</li>";
                }
            date_default_timezone_set($default_timezone);
            $message .= "</ul>".EMAIL_BODY_POST;
           $additional_headers = "From: ".WEBMASTER_EMAIL."\r\nX-Mailer: Rastel ".VERSION;

            $mailResult = mail($to,$subject,$message,$additional_headers);

            //If the email attempt fails, we note this but leave it.
            if($mailResult){
                $notificationSentResult = $db->execute(
                    "UPDATE location_recording SET correlated_ind = 3 WHERE user_id = "
                    .$notification["user_id"]." AND correlated_ind = 2");
                if($notificationSentResult){
                    $db->commit();
                    $emailSuccesses++;
                }
                else {$db->rollback();}
            }
            else {$db->rollback();}
        }
        log($emailSuccesses."/".$emailAttempts." emails sent.");

        log("Process complete!");

    function log($string){
        echo "[".date('c',time())."] ".$string.PHP_EOL;
    }
?>