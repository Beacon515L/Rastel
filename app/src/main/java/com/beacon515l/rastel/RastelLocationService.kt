package com.beacon515l.rastel

import android.annotation.SuppressLint
import android.app.IntentService
import android.content.Intent
import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.CancellationTokenSource

private const val ACTION_LOCATE = "com.beacon515l.rastel.action.locate"
private const val ACTION_REPORT = "com.beacon515l.rastel.action.report"

private lateinit var fusedLocationClient: FusedLocationProviderClient

/**
 * An [IntentService] subclass for handling asynchronous task requests in
 * a service on a separate handler thread.

 * TODO: Customize class - update intent actions, extra parameters and static
 * helper methods.

 */
class RastelLocationService : IntentService("RastelLocationService") {

    override fun onHandleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_LOCATE -> {
                handleActionLocate()
            }
            ACTION_REPORT -> {
                handleActionReport()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleActionLocate() {
        val db = DBHelper(this,null)
        var config: UserConfiguration?
        while (true){
            //Retrieve the current configuration.
            config = db.getUserConfiguration()
            if(config == null || !updateStatus(config,true)){
                //Abort immediately if unable to retrieve config at all, or if this service should abort.
                return
            }

            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)


            //In all other circumstances this service should run.  It is just a question of how often.
            val delay = ((config.locateFrequency ?: RastelCommon.DEFAULT_LOCATE_FREQUENCY * 1000).toLong())

            //Get the current location and time.
            val cancellationTokenSource = CancellationTokenSource()
            val locationTask = fusedLocationClient
                .getCurrentLocation(LocationRequest.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)

                //Synchronously await the task's result.
                //This will wait up to thirty seconds, fifty milliseconds at a time.
                var i = 0
                while (i < 600){
                    if(locationTask.isComplete){
                        break
                    }
                    Thread.sleep(50)
                    i++
                }
                if(locationTask.isSuccessful){
                    val location = locationTask.result
                    val time = System.currentTimeMillis() / 1000
                    db.addLocation(location.latitude,location.longitude,time)
                }

            Thread.sleep(delay)
        }
    }


    private fun handleActionReport() {
        TODO("Handle action Report")
    }

    companion object {
        /**
         * Starts this service to perform action Foo with the given parameters. If
         * the service is already performing a task this action will be queued.
         *
         * @see IntentService
         */
        // TODO: Customize helper method
        @JvmStatic
        fun startActionLocate(context: Context) {
            val intent = Intent(context, RastelLocationService::class.java).apply {
                action = ACTION_LOCATE
            }
            context.startService(intent)
        }

        /**
         * Starts this service to perform action Baz with the given parameters. If
         * the service is already performing a task this action will be queued.
         *
         * @see IntentService
         */
        // TODO: Customize helper method
        @JvmStatic
        fun startActionReport(context: Context) {
            val intent = Intent(context, RastelLocationService::class.java).apply {
                action = ACTION_REPORT
            }
            context.startService(intent)
        }
    }
    //Updates the stored service status depending on what is requested.
    private fun updateStatus(config: UserConfiguration, isLocationService: Boolean): Boolean{
        val state = config.state ?: RastelCommon.Companion.serviceStatus.STOP.code
        val requestedState = config.requestedState ?: RastelCommon.Companion.serviceStatus.STOP.code
        var finalState: Int
        var locationServiceIsRunning: Boolean = false
        var reportingServiceIsRunning: Boolean = false
        val locationServiceShouldBeRunning = requestedState ==
                    RastelCommon.Companion.serviceStatus.LOCATE_ONLY.code ||
                requestedState == RastelCommon.Companion.serviceStatus.RUN.code
        val reportingServiceShouldBeRunning = requestedState ==
                RastelCommon.Companion.serviceStatus.REPORT_ONLY.code ||
                requestedState == RastelCommon.Companion.serviceStatus.RUN.code

        //The service which is calling this method is assumed to be running, and will be transitioning
        //to the requested state if necessary immediately after this call.
        if(isLocationService){
            locationServiceIsRunning = requestedState ==
                    RastelCommon.Companion.serviceStatus.LOCATE_ONLY.code ||
                    requestedState == RastelCommon.Companion.serviceStatus.RUN.code
        }
        else {
            reportingServiceIsRunning = requestedState ==
                    RastelCommon.Companion.serviceStatus.REPORT_ONLY.code ||
                    requestedState == RastelCommon.Companion.serviceStatus.RUN.code
        }

        //The other service is assumed to be in whatever state is already being indicated.
        if(isLocationService){
            reportingServiceIsRunning = state ==
                    RastelCommon.Companion.serviceStatus.REPORT_ONLY.code ||
                    state == RastelCommon.Companion.serviceStatus.RUN.code
        }
        else {
            locationServiceIsRunning = state ==
                    RastelCommon.Companion.serviceStatus.LOCATE_ONLY.code ||
                    state == RastelCommon.Companion.serviceStatus.RUN.code
        }

        //Despite all this, if Google Play Services aren't available, the Location service cannot run.
        if(GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this)
            != ConnectionResult.SUCCESS){
            locationServiceIsRunning = false
        }

        //On this basis, now set the state.
        finalState = if(locationServiceIsRunning){
            if(reportingServiceIsRunning){
                RastelCommon.Companion.serviceStatus.RUN.code
            } else {
                RastelCommon.Companion.serviceStatus.LOCATE_ONLY.code
            }
        } else {
            if(reportingServiceIsRunning){
                RastelCommon.Companion.serviceStatus.REPORT_ONLY.code
            } else {
                RastelCommon.Companion.serviceStatus.STOP.code
            }
        }

        //Update the configuration state to this value.
        config.state = finalState
        val db = DBHelper(this,null)
        db.updateUserConfiguration(config)

        //Finally, return whether the requested service SHOULD continue running.
        return if(isLocationService){
            locationServiceIsRunning
        } else {
            reportingServiceIsRunning
        }
    }
}