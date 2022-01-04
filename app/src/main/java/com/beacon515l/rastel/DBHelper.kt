package com.beacon515l.rastel

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DBHelper(context: Context, factory: SQLiteDatabase.CursorFactory?) :
    SQLiteOpenHelper(context, "RastelDB", factory, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        var query = ("CREATE TABLE location_recording (" +
                "recorded_date_time INTEGER PRIMARY KEY," +
                "latitude NUMERIC," +
                "longitude NUMERIC," +
                "correlated_ind INTEGER)")

        db.execSQL(query)

        //Create a table to store the user's credentials.
        query = ("CREATE TABLE user (" +
                "user_id INTEGER PRIMARY KEY," +
                "email TEXT," +
                "password TEXT," +
                "bearer_token TEXT," +
                "service_status INTEGER," +
                "requested_service_status INTEGER," +
                "api_url TEXT," +
                "locate_frequency INTEGER," +
                "report_frequency INTEGER")

        db.execSQL(query)
    }

    override fun onUpgrade(db: SQLiteDatabase, p1: Int, p2: Int) {
        db.execSQL("DROP TABLE IF EXISTS location_recording")
        db.execSQL("DROP TABLE IF EXISTS user")
        onCreate(db)
    }

    // Record a location.
    fun addLocation(lat : Double, long : Double, time: Long ){
        val values = ContentValues()
        values.put("recorded_date_time", time)
        values.put("latitude", lat)
        values.put("longitude", long)
        val db = this.writableDatabase
        db.insert("location_recoding", null, values)
        db.close()
    }

    // Update the user configuration.
    fun updateUserConfiguration(
            user: UserConfiguration
            ){
        val values = ContentValues()
        values.put("user_id", user.userId)
        values.put("email", user.email)
        values.put("password", user.password)
        values.put("bearer_token", user.token)
        values.put("service_status", user.state)
        values.put("requested_service_status", user.requestedState)
        values.put("api_url", user.url)
        values.put("locate_frequency", user.locateFrequency)
        values.put("report_frequency",user.reportFrequency)
        val db = this.writableDatabase
        db.beginTransaction() //Avoids theoretical race condition in insertWithOnConflict
        db.insertWithOnConflict("location_recoding", null, values,
            SQLiteDatabase.CONFLICT_REPLACE)
        db.setTransactionSuccessful()
        db.endTransaction()
        db.close()
    }

    //Retrieve the list of locations.
    fun getLocations(): Cursor? {
        val db = this.readableDatabase
        return db.rawQuery("SELECT * FROM location_recording", null)
    }

    //Retrieve the user configuration.
    @SuppressLint("Range")
    fun getUserConfiguration(): UserConfiguration? {
        val user: UserConfiguration = UserConfiguration()
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM user", null)
        if(cursor?.moveToFirst() != true){
            return null
        }
        user.userId = cursor.getInt(cursor.getColumnIndex("user_id"))
        user.email = cursor.getString(cursor.getColumnIndex("email"))
        user.password = cursor.getString(cursor.getColumnIndex("password"))
        user.token = cursor.getString(cursor.getColumnIndex("bearer_token"))
        user.state = cursor.getInt(cursor.getColumnIndex("service_status"))
        user.requestedState = cursor.getInt(cursor.getColumnIndex("requested_service_status"))
        user.url = cursor.getString(cursor.getColumnIndex("api_url"))
        user.locateFrequency = cursor.getInt(cursor.getColumnIndex("locate_frequency"))
        user.reportFrequency = cursor.getInt(cursor.getColumnIndex("report_frequency"))

        return user
    }
}

class UserConfiguration {
    var userId: Int? = null
    var email: String? = null
    var password: String? = null
    var token: String? = null
    var state: Int? = null
    var requestedState: Int? = null
    var url: String? = null
    var locateFrequency: Int? = null
    var reportFrequency: Int? = null
}