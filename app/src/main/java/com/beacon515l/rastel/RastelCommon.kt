package com.beacon515l.rastel

class RastelCommon {
    companion object {
        //Common methods and enums used throughout the client.
        //TODO: Define the default URL
        val DEFAULT_RASTEL_URL: String = "set";
        val DEFAULT_LOCATE_FREQUENCY: Int = 60;  //1 minute
        val DEFAULT_REPORT_FREQUENCY: Int = 900; //15 minutes

        enum class serviceStatus(val code: Int) {
            STOP(0),
            RUN(1),
            LOCATE_ONLY(2),
            REPORT_ONLY(3)
        }
    }
}