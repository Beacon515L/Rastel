<?php
    //THIS ENTIRE FILE IS SENSITIVE. ONCE POPULATED, DO NOT SHARE UNLESS YOU KNOW WHAT YOU ARE DOING.

    //Database configuration parameters.
    DATABASE_CONNECTION_STRING = "mysql:dbname=contact_trace;host=localhost";
    DATABASE_USER = "contact_trace";
    DATABASE_PASSWORD = "Qs7A1L3orluetgK9";

    //JWT HMAC RSA private key path (.PEM or string literal) and passphrase.
    //The application will require the public key.
    RSA_PRIVATE_KEY_PATH = "";
    RSA_PRIVATE_KEY_PASSPHRASE = null;

    //Public key path or string literal for this private key.
    RSA_PUBLIC_KEY_PATH = "";

    //Webmaster email.  Required for sending notifications.
    WEBMASTER_EMAIL = "noreply@test.com";

    //Email subject.
    EMAIL_SUBJECT = "Rastel COVID-19 Contact Alert";

    //Email body before the contact list.
    EMAIL_BODY_PRE = "<p>To whom it may concern,</p>
                      <p>The Rastel COVID-19 contact tracing system has identified people with which you may have come into contact, who have now tested positive for COVID-19.</p>
                      <p>Windows during which you may have been exposed are:</p>";

    //Email body after the contact list.
    EMAIL_BODY_POST = "<p>Review your app for more details.</p>
                       <p>It is recommended that you obtain a COVID test as soon as possible and monitor for symptoms, in accordance with government regulations.</p>
                       <p><br />
                       </p>
                       <p>Thank you for using Rastel; stay safe and well.</p>
                       <p>Regards,</p>
                       <p>Liam Walker-Greenough</p>
                       <p>Rastel developer</p>";

    //Date format ($format parameter of date()).
    DATE_FORMAT = "DATE_RFC2822";

    //Times below are all in seconds.
    //-------------------------------//

    //How old data has to be to be processed at all.  Optimally as long as the crontab runs to keep
    //the queue size relatively constant.
    PROCESSING_DELAY = 900; //15 minutes

    //How long ago the last data to be correlated must have been processed, before the next crontab
    //run. Effectively determines batch run frequency.
    PROCESSING_INTERVAL = 900; //15 minutes

    //How long to retain correlated data in case of positive test results.
    //Data older than this, no matter what state, is scrubbed.
    //Should be determined both from the actual measured infectious period for COVID-19,
    //and the average lead time on test results.
    INFECTIOUS_PERIOD = 1209600; //14 days - may be excessive

    //How long it should be assumed a user is in quarantine following a positive test,
    //if they do not otherwise specify and notwithstanding a subsequent negative test.
    DEFAULT_QUARANTINE_PERIOD = 604800; //7 days

    //How old observations must be before they will get rejected by the API.
    //Should generally be as long as the infectious period.
    LOCATION_AGE_GATE = 1209600; //14 days

    //Time resolution.  All dates and times will be conformed to the nearest preceding interval
    //of this many seconds.
    TIME_RESOLUTION = 60; //1 minute


    //How long two people must be in contact with one another before they are considered close contacts.
    //In spatial terms, two people are considered in contact with each other for as long as they have
    //locations with the same coordinates after rounding (defined by the scaling factor, below).
    //The time is then defined as at least X (MIN) within a period of Y (MAX) seconds.
    CLOSE_CONTACT_TIME_MIN = 60;
    CLOSE_CONTACT_TIME_MAX = 360;

    //How long logged-in JWTs last.
    BEARER_TOKEN_EXPIRY = 259200; //3 days

    //How long before the expiry time to renew JWTs.
    BEARER_TOKEN_RENEW = 10800; //3 hours

    //How long to wait (maximum and minimum) to simulate password hashing time in the event of an
    //account not being found, in milliseconds.  This is to obfuscate account existence.
    //The actual interval is a random value between these limits.  The values should be determined
    //by benchmarking password_verify() and SELECT * FROM user WHERE email_address = ''
    //on the target system.
    PASSWORD_HASH_SIMULATE_MIN = 500;
    PASSWORD_HASH_SIMULATE_MAX = 1000;

    //Coordinate scaling factor.
    //Decimal coordinates are multiplied by this number and then rounded down before Cantor-pairing.
    //The inverse operation obtains the original coordinates.
    //This number is critical to the accuracy of the system and changing it invalidates previous data.
    //A value of 1000 leaves four decimal places, with an effective accuracy of 11 metres.
    COORDINATE_SCALING_FACTOR = 1000;
?>