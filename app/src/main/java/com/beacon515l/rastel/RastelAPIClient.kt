package com.beacon515l.rastel

import android.content.Context
import java.util.TimeZone
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

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

    val TEST_API_URL = "/rest/v1/test"
    val TEST_GET_METHOD = RequestType.GET
    val TEST_ADD_METHOD = RequestType.POST

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


    fun register(): Boolean {
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
            USER_ADD_METHOD,
            USER_ADD_NEEDS_AUTH,
            JSONObject(requestObject as Map<String, String>).toString(),
            null
        )
        if(response?.responseCode != 200 || response.payload == null){ return false }
        else {
            val responseJson = JSONObject(response.payload)
                //Persist the login details.
                config.userId = responseJson["id"] as Int?
                config.email = email
                config.password = password
                val db = DBHelper(context,null)
                db.updateUserConfiguration(config)
            return true
        }

        return false
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