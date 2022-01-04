package com.beacon515l.rastel

import android.annotation.SuppressLint
import android.content.Context
import java.util.TimeZone
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.lang.NumberFormatException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.floor
import kotlin.math.sqrt

class RastelAPIClient(var config: UserConfiguration, val context: Context) {
    enum class RequestType {
        GET, POST, PUT
    }

    val CONTENT_TYPE = "application/json"
    val CONTENT_ENCODING = Charsets.UTF_8

    val SERVER_TIME_URL = "/rest/v1/serverTime"
    val SERVER_TIME_METHOD = RequestType.GET
    val SERVER_TIME_NEEDS_AUTH = false

    val USER_API_URL = "/rest/v1/user"
    val USER_GET_METHOD = RequestType.GET
    val USER_ADD_METHOD = RequestType.POST
    val USER_MOD_METHOD = RequestType.PUT
    val USER_GET_NEEDS_AUTH = false //In the sense that it doesn't need a JWT
    val USER_ADD_NEEDS_AUTH = false
    val USER_MOD_NEEDS_AUTH = true

    val LOG_API_URL = "/rest/v1/log"
    val LOG_GET_METHOD = RequestType.GET
    val LOG_ADD_METHOD = RequestType.POST
    val LOG_ADD_NEEDS_AUTH = true
    val LOG_GET_NEEDS_AUTH = true

    val TEST_API_URL = "/rest/v1/test"
    val TEST_GET_METHOD = RequestType.GET
    val TEST_ADD_METHOD = RequestType.POST
    val TEST_ADD_NEEDS_AUTH = true
    val TEST_GET_NEEDS_AUTH = true

    var serverTime: Long = 0
    var localTime: Long = 0

    //Must agree with constants defined in the API.
    //TODO: Replace this with endpoints rather than hardcoding it, since the PHP can be changed
    val TIME_RESOLUTION: Long = 60
    val COORDINATE_SCALING_FACTOR = 1000

    fun configIsUsable(): Boolean {
        //All of the following fields must be set.
        if (config.email.isNullOrEmpty() || config.password.isNullOrEmpty() || config.url.isNullOrEmpty()) {
            return false
        }
        return true
    }

    fun httpRequest(
            config: UserConfiguration,
            url: String,
            type: RequestType,
            includeAuth: Boolean,
            payload: String?,
            extraHeaders: HashMap<String,String>?): HTTPResponse?{
        if(!configIsUsable() || (config.token == null && includeAuth)){
            return null
        }
        val urlObj = URL(url)
        val connection = (urlObj.openConnection() as HttpURLConnection)
        connection.requestMethod = type.name
        connection.setRequestProperty("Content-Type",CONTENT_TYPE)
        connection.useCaches = false
        connection.doInput = false
        connection.doOutput = true
        if(includeAuth){
            connection.setRequestProperty("Authorization","Bearer " + config.token)
        }
        if(extraHeaders != null){
            for (key in extraHeaders.keys){
                connection.setRequestProperty(key,extraHeaders[key])
            }
        }
        if(type != RequestType.GET || payload != null){
            connection.doInput = true
            connection.setRequestProperty("Content-Length", (payload ?: "")
                .toByteArray(CONTENT_ENCODING).size.toString())
            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(payload)
            writer.flush()
        }

        var responsePayload: String?

        BufferedReader(InputStreamReader(connection.inputStream)).use {
            val response = StringBuffer()
            var line = it.readLine()
            while(line != null){
                response.append(line)
                line = it.readLine()
            }
            responsePayload = response.toString()
            val r = responsePayload
            if(r == null || r.isEmpty()) {responsePayload = null}
        }

        val responseToken = connection.getHeaderField("Authorization")


        return HTTPResponse(connection.responseCode, responsePayload, responseToken)
    }

    fun serverTime(): Long? {
        val response = httpRequest(
            config,
            config.url + SERVER_TIME_URL,
            SERVER_TIME_METHOD,
            SERVER_TIME_NEEDS_AUTH,
            null,
            null
        )
        return try {
        if(response != null && response.responseCode == 200){
            localTime = System.currentTimeMillis()
            serverTime = response.payload?.toLong() ?: localTime
            response.payload?.toLong()
        }
        else null
        } catch (e: NumberFormatException){ null }
    }

    //Obtain a JWT and store it.
    fun login(): Boolean {
        val email = config.email ?: ""
        val password = config.password ?: ""
        val url = (config.url ?: "") + USER_API_URL
        val extraParams = HashMap<String,String>()
        extraParams["Authorization"] = "Basic " +
                Base64.encode(("$email:$password").toByteArray(CONTENT_ENCODING),Base64.DEFAULT)

        val response = httpRequest(
            config,
            url,
            USER_GET_METHOD,
            USER_GET_NEEDS_AUTH,
            null,
            extraParams)

        if(response?.responseCode != 200 || response.token == null){ return false }

        val jwt = JWT(config.token, context)
        jwt.parse()
        if(jwt.valid) {
            config.email = email
            config.password = password
            jwt.writeJWT(config)
            return true
        }
        return false
    }


    fun registerOrUpdate(update: Boolean): Boolean {
        val email = config.email ?: ""
        val password = config.password ?: ""
        val url = (config.url ?: "") + USER_API_URL


        val requestObject = HashMap<String,String>()
        requestObject["email"] = email
        requestObject["password"] = password
        requestObject["timezone"] = TimeZone.getDefault().id

        val response = httpRequest(
            config,
            url,
            if (update) USER_MOD_METHOD else USER_ADD_METHOD,
            if (update) USER_MOD_NEEDS_AUTH else USER_ADD_NEEDS_AUTH,
            JSONObject(requestObject as Map<String, String>).toString(),
            null
        )
        return if(response?.responseCode != 200 || response.payload == null){
            false
        } else {
            val responseJson = JSONObject(response.payload)
            //Persist the login details.
            config.userId = responseJson["id"] as Int?
            config.email = email
            config.password = password
            val db = DBHelper(context,null)
            db.updateUserConfiguration(config)
            true
        }
    }


    @SuppressLint("Range")
    fun locationLog(write: Boolean): List<LogEntry>{
        val localLogs = ArrayList<LogEntry>()
        val db = DBHelper(context,null)
        val logCursor = db.getLocations()

        if(logCursor != null && logCursor.moveToFirst()){
            var stop = false
            while(!stop){
                val latitude = logCursor.getDouble(logCursor.getColumnIndex("latitude"))
                val longitude = logCursor.getDouble(logCursor.getColumnIndex("longitude"))
                val time = logCursor.getLong(logCursor.getColumnIndex("recorded_date_time"))
                val correlationStatus =
                    when(logCursor.getInt(logCursor.getColumnIndex("correlated_ind"))){
                        -1 -> LogEntry.CorrelationStatus.LOCAL_ONLY
                        0 -> LogEntry.CorrelationStatus.UNCORRELATED
                        1 -> LogEntry.CorrelationStatus.CORRELATED
                        2 -> LogEntry.CorrelationStatus.FLAGGED
                        3 -> LogEntry.CorrelationStatus.NOTIFIED
                        else -> LogEntry.CorrelationStatus.LOCAL_ONLY
                }
                localLogs[localLogs.size] = LogEntry(latitude,longitude,time,correlationStatus)
                stop = !logCursor.moveToNext()
            }
        }
        val uploadLogs = ArrayList<LogEntry>()
        //If we're writing, transmit local logs only - so first remove any non-local logs.
        if(write){
            for (log in localLogs){
                if(log.correlation == LogEntry.CorrelationStatus.LOCAL_ONLY){
                    uploadLogs[localLogs.size] = log
                }
            }
        }

        val response = httpRequest(
            config,
            LOG_API_URL,
            if(write) LOG_ADD_METHOD else LOG_GET_METHOD,
            if(write) LOG_ADD_NEEDS_AUTH else LOG_GET_NEEDS_AUTH,
            if(write) JSONArray(uploadLogs).toString() else null,
            null
        )

        val outputList = ArrayList<LogEntry>()

        if(response != null && response.responseCode == 200 && response.payload != null){
            //Retrieve the output log set.
            val returnedLocations = JSONArray(response.payload)
            //A server log and local log are considered one and the same when the local log's time
            // is up to TIME_INTERVAL seconds later than the local log, and is the least distance
            // from that log.
            //This has to take into account the difference in time between the server and the client.
            val logMap = HashMap<Int,Int?>()
            var serverLogIdx = 0
            while (serverLogIdx < returnedLocations.length()){
                var localLogIdx = 0
                var localLogCandidateIdx: Int? = null
                val serverLog = returnedLocations[serverLogIdx]
                var minTimeDelta: Long = TIME_RESOLUTION
                while (localLogIdx < localLogs.size){
                    val localLog = localLogs[localLogIdx]
                    val timeDelta = localLog.time -
                            ((serverLog as JSONObject)["time"] as Int)-
                            (localTime - serverTime)
                    if(timeDelta in 0 until minTimeDelta){
                        localLogCandidateIdx = localLogIdx
                        minTimeDelta = timeDelta
                    }
                    localLogIdx++
                }
                    logMap[serverLogIdx] = localLogCandidateIdx
                serverLogIdx++
            }

            //Now use this map to correlate the logs.
            //We use the time from the local logs, but the status from the server's logs.
            //Server logs not correlated with local logs are loaded verbatim.
            //Local logs not correlated are marked as permanently not transmitted.
            for (serverLogIndex in logMap.keys){
                val serverLog = returnedLocations[serverLogIndex]
                val localLog = if(logMap[serverLogIndex]!=null) localLogs[logMap[serverLogIndex]!!] else null
                val serverLogStatus = when((serverLog["correlated_ind"] as Int)){
                    -2 -> LogEntry.CorrelationStatus.LOCAL_ONLY_REJECTED
                    -1 -> LogEntry.CorrelationStatus.LOCAL_ONLY
                    0 -> LogEntry.CorrelationStatus.UNCORRELATED
                    1 -> LogEntry.CorrelationStatus.CORRELATED
                    2 -> LogEntry.CorrelationStatus.FLAGGED
                    3 -> LogEntry.CorrelationStatus.NOTIFIED
                    else -> LogEntry.CorrelationStatus.LOCAL_ONLY_REJECTED
                }
                val coalescedLog =
                    if(localLog != null)
                    {localLog.correlation = serverLogStatus
                        localLog
                    }
                    else {
                        //Cantor unpair the log as it isn't known to the client.
                        val z = ((serverLog as JSONObject)["cantor_coordinates"] as Double)
                        val quadrant = ((serverLog as JSONObject)["cantor_quadrant"] as Int)
                        val serverLogTime =
                            ((serverLog as JSONObject)["recorded_date_time"] as Long) - (localTime - serverTime)
                        val t = floor((-1 + sqrt(1 + 8 * z))/2)
                        val x = (t * (t + 3) / 2 - z) / COORDINATE_SCALING_FACTOR *
                                if(quadrant == 0 || quadrant == 1) 1 else -1
                        val y = (z - t * (t + 1) / 2) / COORDINATE_SCALING_FACTOR *
                                if(quadrant == 0 || quadrant == 3) 1 else -1
                        LogEntry(x,y,serverLogTime,serverLogStatus)
                    }
                outputList.add(coalescedLog)
            }
            //Interleave all the existing local logs, now to be marked as rejected.
            for (localLog in localLogs){
                if(localLog.correlation == LogEntry.CorrelationStatus.LOCAL_ONLY){
                    localLog.correlation == LogEntry.CorrelationStatus.LOCAL_ONLY_REJECTED
                    outputList.add(localLog)
                }
            }
            //Update the database for all these logs
            for (log in outputList){
                db.addLocation(log)
            }
        }
    return outputList
    }

    fun covidTest(test: CovidTest): List<CovidTest>{
        val retValue = List<CovidTest>()
        val db = DBHelper(context,null)

    }

}




class JWT (var token: String?, val context: Context){
    var headerStr: String? = null
    var bodyStr: String? = null
    var signature: String? = null
    var header: JSONObject? = null
    var body: JSONObject? = null
    var initialized: Boolean = false
    var valid: Boolean = false

    fun parse(): Boolean{
        val tokenValue = token ?: return false
        headerStr = null; bodyStr = null; signature = null
        header = null; body = null; initialized = false
        val splitToken = tokenValue.split('.')
        if(splitToken.size != 3){
            //JWT is malformed
            return false
        }
        headerStr = Base64.decode(splitToken[0],Base64.DEFAULT).toString()
        bodyStr = Base64.decode(splitToken[1],Base64.DEFAULT).toString()
        signature = splitToken[2]

        header = JSONObject(headerStr ?: "")
        body = JSONObject(bodyStr ?: "")

        if(header == null || body == null){
            return false
        }
        initialized = true
        val jsonBody = body
        val time = System.currentTimeMillis() / 1000

        //The token needs to not be expired and not issued in the past.
        val expiry: Int? = (jsonBody?.get("expiry") as Int?)
        val issuedAt: Int? = (jsonBody?.get("iat") as Int?)
        val userId: Int? = (jsonBody?.get("userId") as Int?)
        valid = expiry ?: 0 > time && issuedAt ?: 0 < time && userId != null
        //TODO: Implement JWT signature validation; assumed for now
        return true
    }

    fun writeJWT(c: UserConfiguration? = null): Boolean{
        if(!valid) return false
        var config = c
        val db = DBHelper(context,null)
        if(config == null) {config = db.getUserConfiguration()}
        if(config != null && config.token != token){
            config.token = token
            config.userId = (body?.get("userId") as Int?)
            db.updateUserConfiguration(config)
            return true
        }
        return false
    }

    fun readJWT(): Boolean{
        val db = DBHelper(context, null)
        val config = db.getUserConfiguration()
        token = config?.token
        return parse()
    }

}

data class HTTPResponse (val responseCode: Int, val payload: String?, val token: String?){}
data class LogEntry (val lat: Double, val long: Double, val time: Long,
                    var correlation: CorrelationStatus){
    enum class CorrelationStatus(code: Int) {
        LOCAL_ONLY_REJECTED(-2),
        LOCAL_ONLY(-1),
        UNCORRELATED(0),
        CORRELATED(1),
        FLAGGED(2),
        NOTIFIED(3)
    }
}