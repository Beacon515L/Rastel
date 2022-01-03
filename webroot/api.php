<?php

// GET /rest/v1/serverTime
//Gets the current time in UTC.  This is its own API call for client expedience.
function getServerTime(){
    return time();
}

// POST /rest/v1/user
//Attempts to register a user.
function postUser($email, $password,$timezone){
    //The supplied email has to be valid.
    require_once 'vendor/dominicsayers/is_email.php';
    if(!is_email($email)){
        throw new Exception("NO_EMAIL");
    }

    //The supplied time zone has to be valid.
    require_once 'timezones.php';
    $matched = false;
    foreach ($timezones as $continent){
        foreach ($continent as $tz){
            if ($timezone == $tz) {$matched = true; break;}
        }
        if($matched) break;

    if(!$matched){
        throw new Exception("INVALID_TIMEZONE");
    }

    //Hash the supplied password for this user.
    $passwordHash = password_hash($password);

    //Within transaction control, verify and insert the record.
    $userRecord = [
            "id"=>null,
            "email" => $email,
            "verified" => false
        ];

    try {
        $db = dbInit();
        $checkStmt = $db->prepare("SELECT id, verified_email FROM user WHERE email = :e LIMIT 1");
        $checkResult = $checkStmt->execute(["e"=>$email]);
        $checkResults = $checkResult->fetchAll(PDO::FETCH_ASSOC);

        $db->beginTransaction();

        //If exactly one result is returned, the email is known to the system.
        //If it is unverified, it effectively doesn't exist; just update the password
        //and timezone unconditionally.
        //Otherwise, block.
        if(count($checkResults) == 1){
            $userRecord["id"] = $checkResults[0]["id"];

            if($checkResults[0]["verified_email"] == 1){
                throw new Exception("ALREADY_REGISTERED");
            }

            $updateStmt = $db->prepare("UPDATE user SET password_hash = :hash,",
            "timezone = :tz WHERE id = :id");
            $updateResult = $updateStmt->execute(["hash"=>$password_hash,"id"=>$userRecord["id"],
            "tz"=>$timezone);
            if(!$updateResult){
                throw new Exception("DATABASE_ERROR");
            }
        }
        else {
            //The record isn't known to the system. Insert it.
            $insertStmt = $db->prepare("INSERT INTO user (email,password_hash,timezone) VALUES (:e,:h, :tz)");
            $insertResult = $insertStmt->execute(["e"=>$email,"h"=>$password_hash,"tz"=>$timezone]);
            if(!$insertResult){
                throw new Exception("DATABASE_ERROR");
            }

            //Now retrieve the ID for the user just inserted.
            $checkResult = $checkStmt->execute(["e"=>$email]);
            $checkResults = $checkResult->fetchAll(PDO::FETCH_ASSOC);

            //There should now be exactly one record.  If there isn't, that is a problem.
            if(count($checkResults !== 1){
                throw new Exception("DATABASE_ERROR");
            }

            $userRecord["id"] = $checkResults[0]["id"];
        }

        $db->commit();
    }
    catch (Exception $e){if ($db->inTransaction()) $db->rollback(); throw $e;}

    return $userRecord;
}

// PUT /rest/v1/user
//Attempts to update a user's email and/or password.
function putUser($userId,$email,$password,$oldPassword,$timezone){
        //The JWT must be valid and must agree with the specified customer.
        //This will throw an exception if it fails.
        $jwt = validateJWT($jwt,$userId);

        //The supplied email has to be valid.
        require_once 'vendor/dominicsayers/is_email.php';
        if(!is_email($email)){
            throw new Exception("NO_EMAIL");
        }

        //The supplied time zone has to be valid.
        require_once 'timezones.php';
        $matched = false;
        foreach ($timezones as $continent){
            foreach ($continent as $tz){
                if ($timezone == $tz) {$matched = true; break;}
            }
            if($matched) break;
        }

        if(!$matched){
            throw new Exception("INVALID_TIMEZONE");
        }

        //Hash the supplied password for this user.
        $passwordHash = password_hash($password);
        $userRecord = null;

        //Retrieve the user's details.
        try {
            $db = dbInit();
            $checkStmt = $db->prepare("SELECT id,email,password_hash,verified_email AS verified"
                ."FROM user WHERE id = :id AND verified_email = 1 LIMIT 1");
            $checkResult = $checkStmt->execute(["id"=>$userId]);
            $checkResults = $checkResult->fetchAll(PDO::FETCH_ASSOC);

            if(count($checkResults !== 1){
                throw Exception("DATABASE_ERROR");
            }

            $userRecord = $checkResults[0];

            //Check the password on this record before proceeding.  Then redact it.
            if(!password_verify($oldPassword,$userRecord["password_hash"]){
                throw Exception("AUTH_FAIL");
            }
            unset $userRecord["password_hash"];

            //Now update the email and password on this record.
            $db->beginTransaction();
            $updateStmt =
            $db->prepare("UPDATE user SET email = :e, password_hash = :h, ",
            "timezone = :tz WHERE id = :id");
            $updateResult = $updateStmt->execute(["e"=>$email,"h"=>$password_hash,"id"=>$userId,
            "tz" => $timezone]);
            if(!$updateResult){
                throw new Exception("DATABASE_ERROR");
            }
            $db->commit();
        }
        catch (Exception $e){if($db->inTransaction()) $db->rollback(); throw $e;}

    return $userRecord;
}

// GET /rest/v1/user
//Obtains a JWT given an email and password.
function getUser($email, $password){
    //The email has to be set.  Strictly speaking the password does not (it could be empty string).
    if(empty($email)) {
        throw new Exception("NO_EMAIL");
    }

    //Retrieve the stored hash.
    $hash = null;
    $db = dbInit();
    $stmt = $db->prepare("SELECT id, email, password_hash FROM user WHERE email = :e"
    ." AND verified_ind = 1 LIMIT 1");
    $stmt->exec(["e"=>$email]);
    $result = $stmt->fetch(PDO::FETCH_ASSOC);

    if($result&&is_array($result)&&array_key_exists("password_hash")){
        $hash = $result["password_hash"];
    }

    unset($db);

    //If password retrieval failed, wait a random amount of time before failing.
    //This obfuscates account existence.
    if(empty($hash)){
        $waitMilliseconds = rand(PASSWORD_HASH_SIMULATE_MIN,PASSWORD_HASH_SIMULATE_MAX);
        sleep((float) waitMilliseconds / 1000.0);
        throw new Exception("AUTH_FAIL");
    }

    //Otherwise, validate it for real.
    $passwordResult = password_verify($password, $hash);
    if(!passwordResult){
        throw new Exception("AUTH_FAIL");
    }

    //Generate a JWT.
    return createJWT($result["userId"]);
}

//POST /rest/v1/log
//Writes a new set of locations to the user's log.
function postLog($jwt,$userId,$locations,$serverTime,$localTime){
    //All of the parameters must be set, and $locations must be an array.
    if(empty($userId)||empty($locations)||empty($serverTime)
        ||empty($localTime)||!is_array($locations){
        throw new Exception("BAD_REQUEST");
    }

    //The JWT must be valid and must agree with the specified customer.
    //This will throw an exception if it fails.
    $jwt = validateJWT($jwt,$userId);

    //Work out the delta between the server time, and the client's local time when it checked.
    //This would have been obtained by a prior call to getServerTime().
    //Both are (in theory) UTC Unix timestamps, but this allows us to make no assumptions w.r.t.
    //the correctness of client configuration.
    $timeDelta = $localTime - $serverTime;

    //Use this to adjust the times of all the observations made so as to conform to server time.
    //In so doing, validate each location entry.
    $time = time();

    $validLocations = 0;

    foreach ($locations as $key => $timeLatLong){
        //Ignore locations missing any of the required fields, or if they are out of range.
        if(empty($timeLatLong["time"]||empty($timeLatLong["lat"]||empty($timeLatLong["long"]
            ||$timeLatLong["lat"]>90.0||$timeLatLong["lat"]<-90.0
            ||$timeLatLong["long"]>180.0||$timeLatLong["long"]<=-180.0){
            $locations[$key] = null; continue;
        }

        //If the log is older than the age gate, ignore it.
        $locationServerTime = $timeLatLong["time"] - $timeDelta;
        if($locationServerTime - $time > LOCATION_AGE_GATE){
            $locations[$key] = null; continue;
        }

        //Translate the time and coordinates for this location.
        $translatedTimeLatLong = [];
        $translatedTimeLatLong["time"] = floor($locationServerTime / TIME_RESOLUTION) * TIME_RESOLUTION;
        $translatedTimeLatLong["cantor"] = cantorPair(
                floor($timeLatLong["lat"] * COORDINATE_SCALING_FACTOR),
                floor($timeLatLong["long"] * COORDINATE_SCALING_FACTOR)
             );
        if($timeLatLong["lat"]>=0){
            if($timeLatLong["long"]>=0){
                $translatedTimeLatLong["quadrant"] = 0;
            }
            else $translatedTimeLatLong["quadrant"] = 1;
        }
        else {
            if($timeLatLong["long"]>=0){
                        $translatedTimeLatLong["quadrant"] = 4;
                    }
                    else $translatedTimeLatLong["quadrant"] = 3;
        }

        $locations[$key] = $translatedTimeLatLong;
        $validLocations++;
    }

    //If we have at least one valid location, proceed to write to the database.
    if($validLocations > 0){
        $db = dbInit();

        try{
            $db->beginTransaction();
            //Prepare a statement to write all these locations.
            $stmt = $db->prepare(
            "INSERT INTO location_recording (user_id,cantor_coordinates,cantor_quadrant,recorded_date_time) "
            ."VALUES (".$userId.", :cantor, :quadrant, :time)");

            foreach ($locations as $locationToWrite){
                if($locationToWrite !== null){
                    $stmt->execute($locationToWrite);
                }
            }
            $db->commit();
        }
        catch (Exception $e){
            $db->rollback();
            throw new Exception("DATABASE_ERROR");
        }
       unset($db);
    }

    //Return the final location list, which may be assumed now to be written (except where nulled).
    return $locations;
}

//GET /rest/v1/log
function getLog($userId){
    //The user ID must be set.
    if(empty($userId){
        throw new Exception("BAD_REQUEST");
    }

    //The JWT must be valid and must agree with the specified customer.
    //This will throw an exception if it fails.
    $jwt = validateJWT($jwt,$userId);

    //Initialize the database and retrieve the user's location logs.
    //Pagination shouldn't be necessary yet as the data is very terse and regularly pruned.
    $db = dbInit();
    $result = $db->query(
        "SELECT cantor_coordinates AS cantor,cantor_quadrant AS quadrant,"
        ."recorded_date_time AS time,correlated_ind AS status "
        ."FROM location_recording WHERE user_id = ".$userId);

    $locations = $result->fetchAll(PDO::FETCH_ASSOC);
    unset($db);

    return $locations;
}

//POST /rest/v1/test
//Logs a test result.
function postTest($userId,$type,$positive,$timeTaken,$timeResultReceived,$serverTime,$localTime,
                    $timeLeavingIsolation = null){
    //All of the parameters must be set ($positive, being boolean, is implicit).
        if(empty($userId)||empty($type)||empty($timeTaken)||empty($timeResultReceived)
            ||empty($serverTime)||$empty($localTime)){
            throw new Exception("BAD_REQUEST");
        }

        //The JWT must be valid and must agree with the specified customer.
        //This will throw an exception if it fails.
        $jwt = validateJWT($jwt,$userId);

        //The type must be one recognized by the system, and the result must have been received
        //after the test was taken.
        if($type<0||$type>3||$timeResultReceived<$timeTaken){
            throw new Exception("BAD_REQUEST");
        }

        //Work out the delta between the server time, and the client's local time when it checked.
        //This would have been obtained by a prior call to getServerTime().
        //Both are (in theory) UTC Unix timestamps, but this allows us to make no assumptions w.r.t.
        //the correctness of client configuration.
        $timeDelta = $localTime - $serverTime;

        $parameters = [
                          "userId"                =>  $userId,
                          "type"                  =>  $type,
                          "positive"              =>  $positive ? 1 : 0;
                          "timeTaken"             =>  $timeTaken - $timeDelta,
                          "timeResultReceived"    =>  $timeResultReceived - $timeDelta,
                          "timeLeavingIsolation"  =>  ($timeLeavingIsolation === null)?null:$timeLeavingIsolation - $timeDelta
                      ];

        //All this being done, log the test.
        $db = $dbInit();
        $stmt->prepare(
            "INSERT INTO user_test (user_id,test_type,positive_test,time_taken,time_result_received,time_leaving_isolation) "
            ." VALUES (:userId,:type,:positive,:timeTaken,:timeResultReceived,:timeLeavingIsolation)"
        );
        $result = $stmt->execute($parameters);

        unset($db);

        if(!$result){
            throw new Exception("DATABASE_ERROR");
        }

       return $parameters;
}

//GET /rest/v1/test
//Gets a user's history of tests.
function getTest($userId){
    //The user ID must be set.
    if(empty($userId){
        throw new Exception("BAD_REQUEST");
    }

    //The JWT must be valid and must agree with the specified customer.
    //This will throw an exception if it fails.
    $jwt = validateJWT($jwt,$userId);

    //Initialize the database and retrieve the user's location logs.
    //Pagination shouldn't be necessary yet as the data is very terse and regularly pruned.
    $db = dbInit();
    $result = $db->query(
        "SELECT test_type AS type,positive_test AS positive,"
        ."time_taken AS timeTaken,time_result_received AS timeResultReceived "
        ."FROM user_test WHERE user_id = ".$userId);

    $tests = $result->fetchAll(PDO::FETCH_ASSOC);
    unset($db);

    return $tests;
}

//Helper (non-API) methods
//------------------------//

//Initializes the database connection where required.
function dbInit(){
    require_once("config.php");
    return new PDO(DATABASE_CONNECTION_STRING,DATABASE_USER,DATABASE_PASSWORD);
}

//Creates a JWT.
function createJWT($userId){
    $time = time();

        //Generate a JWT.
        $header = [
            "alg" => "RS256", //RSA HMAC with SHA-256
            "typ" => "JWT"
        ];
        $payload = [
            "iat" => $time,
            "userId" => $userId,
            "expiry" => $time + BEARER_TOKEN_EXPIRY
        ];

        $headerB64 = base64_encode(json_encode($header));
        $payloadB64 = base64_encode(json_encode($payload));
        $token = $headerB64.".".$payloadB64;
        $signature = null;
        $private_key = openssl_pkey_get_private(RSA_PRIVATE_KEY_PATH,RSA_PRIVATE_KEY_PASSPHRASE);
        openssl_sign($token,$signature,$private_key,'sha256');
        $signed = base64_encode($signature);
        return $token.".".$signed;
}

//Validates a JWT.  Used generally for authentication.
function validateJWT($jwt,$validateUserId = null){
    $tokenArray = explode(".",$jwt,2);

    //Exactly two elements (the token and its signature) should be returned.
    if(count($tokenArray < 2) || empty($tokenArray[0] || empty($tokenArray[1]){
        throw new Exception("MALFORMED_TOKEN");
    }
    //We accept now that the token string is superficially valid.
    $token = $tokenArray[0]; $signature = $tokenArray[1];

    //Validate the token.
    $public_key = openssl_pkey_get_public(RSA_PUBLIC_KEY_PATH);
    $verify_result = openssl_verify($token,$signature,$public_key,'sha256');

    if ($verify_result !== 1){
        throw new Exception("MALFORMED_TOKEN");
    }

    //We accept now that the token data agrees with its signature.
    //Parse the token data itself.
    $tokenDataArray = explode(".",$token,2);
    //Exactly two elements (the header and the payload) should be returned.
        if(count($tokenDataArray < 2) || empty($tokenDataArray[0] || empty($tokenDataArray[1]){
            throw new Exception("MALFORMED_TOKEN");
        }

    //We accept now that the token data is superficially valid.
    $headerB64 = $tokenDataArray[0]; $payloadB64 = $tokenDataArray[1];
    $header = json_decode(base64_decode($headerB64),true);
    $payload = json_decode(base64_decode($payloadB64),true);

    if(empty($header)||empty($payload)){
        throw new Exception("MALFORMED_TOKEN");
    }

    //We accept now that the header and payload contain data.
    //Now for logical validation.
    $iat = null; $expiry = null; $userId = null;
    if(array_key_exists($payload["iat"])    $iat = $payload["iat"];
    if(array_key_exists($payload["expiry"]) $expiry = $payload["expiry"];
    if(array_key_exists($payload["userId"]) $userId = $payload["userId"];

    //None of these can be unset.
    if(empty($iat)||empty($expiry)||empty($userId)){
        throw new Exception("MALFORMED_TOKEN");
    }

    //The issued at date must be in the past, and the expiry date must be in the future.
    $time = time();
    if($iat > $time || $expiry < $time){
        throw new Exception("MALFORMED_TOKEN");
    }

    //If the request nominates a specific customer, this has to match the JWT.
        if($validateUserId !== null || $validateUserId !== $userId){
            throw new Exception("AUTH_FAIL");
        }

    //At this point the token is wholly valid.  The only possible issue now is that the customer
    //has been deleted since the token was generated, but that is for other API calls to fail.
    //We then return either the current token, or if it is due to expire, a new one.
    if($expiry - $time < BEARER_TOKEN_RENEW){
        return createJWT($userId);
    }
    else {
        return $jwt;
    }

}

//Performs the Cantor pairing function.
//(https://en.wikipedia.org/wiki/Pairing_function#Cantor_pairing_function).
function cantorPair($x,$y){
    return ()($x + $y) * ($x + $y + 1)) / 2 + $y;
}

//Invert the Cantor pairing function.
function inverseCantorPair($z){
    $t = floor((-1 + sqrt(1 + 8 * $z))/2);
    $x = $t * ($t + 3) / 2 - $z;
    $y = $z - $t * ($t + 1) / 2;
    return [$x, $y];
}

//Error list.  Errors are specified as a HTTP status code, and a message.
//----------//
$errorList = [
    "AUTH_FAIL" => [403,"Authentication failure. Check your email and password and try again."],
    "NO_EMAIL"  =>  [401,"An email address and password are required."],
    "MALFORMED_TOKEN" => [401,"The bearer token supplied is malformed, corrupted or expired."],
    "BAD_REQUEST" => [400,"The request is badly formed."],
    "INVALID_TIMEZONE" => [400,"The timezone specified is invalid."],
    "NO_METHOD" => [404,"The request matches no known endpoint."],
    "DATABASE_ERROR" => [500,"An error occurred while processing the request."],
    "ALREADY_REGISTERED" => [409,"This email address has already been registered and verified."]
];
?>