package com.journeymanager.pleos

import android.car.Car
import android.car.CarInfoManager
import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.vehicle.Vehicle
import android.os.Handler
import android.os.Looper
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * The native half of the bridge. The web app calls PleosNative.request(kind, id, args) through the shim; the answer
 * goes back as window.__pleosResolve(id, json) / window.__pleosReject(id, message).
 *
 * Verified on the Pleos Connect emulator in July 2026 (tsadam-automotive): requestRoute hands off to the built-in
 * navigation; via points attach only after onRouteStarted, two at most (WaypointIndex.FIRST/SECOND); RouteInfo and
 * RequestWaypointInfo take (longitude, latitude, ...) positionally, so every call below names its arguments.
 */
class PleosNative(private val context: Context, private val evaluate: (String) -> Unit) : NaviHelperEventListener {
    private val navi = NaviHelper(context)
    private val vehicle = Vehicle(context)
    // FusedLocation answers the car's position without the built-in navigation (NaviHelper's getCurrentLocationInfo stays
    // silent - "navi not ready" - until the navigation has been opened once after boot, emulator v2.1.0, 2026-11-02).
    private val fused = FusedLocation(context)
    // TextToSpeech on the device: no app registration (Playground "My Project" credentials are for SERVER / HYBRID only).
    private val tts = TextToSpeech(context, Mode.ON_DEVICE)
    // SpeechToText on the device as well; one listening at a time (the last request's id gets the final words).
    private val stt = SpeechToText(context, ai.pleos.playground.stt.constant.Mode.ON_DEVICE)
    private var listening: String? = null
    private var heard = ""
    private val main = Handler(Looper.getMainLooper())
    private var locationWaiters = mutableListOf<String>()
    // The trip's stops watched with FusedLocation proximity alerts (stop id -> alert id), for the web app's stop board
    private val alerts = mutableMapOf<String, String>()
    private var pendingWaypoints: List<Stop> = emptyList()

    data class Stop(val name: String, val latitude: Double, val longitude: Double)

    fun start() {
        runCatching { navi.initialize(); navi.addListener(this) }.onFailure { Log.w(TAG, "navi init", it) }
        runCatching { vehicle.initialize() }.onFailure { Log.w(TAG, "vehicle init", it) }
        runCatching { fused.initialize() }.onFailure { Log.w(TAG, "fused init", it) }
        runCatching {
            tts.initialize()
            tts.setLocale(Locale.KOREA)  // "ko-KR": the on-device engine refuses a locale without a country ("unsupported locale: ko-")
            tts.addEventListener(object : EventListener {
                override fun onStart() { Log.i(TAG, "tts start") }
                override fun onReady() {}
                override fun onDone() { Log.i(TAG, "tts done") }
                override fun onError(type: ErrorType) { Log.w(TAG, "tts error $type") }
                override fun onStop() {}
                override fun onUpdatedRms(rms: Double) {}
            })
        }.onFailure { Log.w(TAG, "tts init", it) }
        runCatching {
            stt.initialize()
            stt.setLocale(Locale.KOREA)
            stt.addListener(object : ResultListener {
                // Partial words go to the page as a DOM event (the web app draws them); the final words answer listen().
                override fun onUpdated(text: String, isFinal: Boolean) {
                    heard = text
                    if (isFinal) endListening(text)
                    else evaluate("window.dispatchEvent(new CustomEvent('pleos-stt', { detail: { text: ${JSONObject.quote(text)} } }))")
                }
                override fun onUpdatedEpdData(start: Long, end: Long) {}
                override fun onStartedRecognition() { Log.i(TAG, "stt started") }
                override fun onEndedRecognition() { Log.i(TAG, "stt ended"); endListening(heard) }
                override fun onError(type: ai.pleos.playground.stt.constant.ErrorType) {
                    Log.w(TAG, "stt error $type")
                    val id = listening ?: return; listening = null; reject(id, "stt $type")
                }
                override fun onReady() {}
                override fun onUpdatedRms(rms: Double) {}
            })
        }.onFailure { Log.w(TAG, "stt init", it) }
        // The Pleos Vehicle SDK's CRN is a DID ("did:cac:42dot:system:devbox::<uuid>" on the emulator): a car identity,
        // not a VIN, so no model can be read from it (checked 2026-09-28). Logged in debug builds only.
        if (BuildConfig.DEBUG) runCatching { vehicle.getCarInfo().getCRNInfo({ crn -> Log.i(TAG, "car CRN='$crn'") }, { e -> Log.w(TAG, "CRN", e) }) }
    }
    fun stop() {
        runCatching { navi.removeListener(this); navi.release() }
        runCatching { vehicle.release() }
        unwatchStops()
        runCatching { fused.release() }
        runCatching { tts.stop(); tts.release() }
        runCatching { stt.stop(); stt.release() }
    }

    @JavascriptInterface
    fun request(kind: String, id: String, args: String) {
        Log.i(TAG, "request $kind #$id ${args.take(200)}")
        try {
            when (kind) {
                "vehicle" -> vehicle(id)
                "car" -> car(id)
                "location" -> location(id)
                "navigate" -> navigate(id, args)
                "speak" -> speak(id, args)
                "listen" -> listen(id)
                "watch-stops" -> watchStops(id, args)
                "unwatch-stops" -> { unwatchStops(); resolve(id, JSONObject().put("watching", 0)) }
                // stop() ends the recording without a last callback (emulator v2.1.0): answer the words so far ourselves
                "listen-stop" -> { runCatching { stt.stop() }; endListening(heard); resolve(id, JSONObject().put("stopped", true)) }
                else -> reject(id, "unknown request $kind")
            }
        } catch (error: Exception) { reject(id, error.message ?: error.javaClass.simpleName) }
    }

    /** Make and model come from the standard Car API (INFO_MAKE / INFO_MODEL; the Pleos Vehicle SDK's CarInfo only gives
     *  the CRN). When the car does not say - or says the emulator's "Hyundai 42dot / Connect" - the build's car stands in;
     *  the web app matches the family in its catalogue. Battery and range come from the car. */
    private fun vehicle(id: String) {
        // The car service can take more than 5 s to come up (emulator boots, 2026-11-14): answer within 3 s with what is
        // known by then - the car's make and model if it said, else the build's car - and fill battery and range when they come.
        val answer = JSONObject().put("maker", BuildConfig.CAR_MAKER).put("model", BuildConfig.CAR_MODEL).put("powertrain", "ev")
            .put("batterySoc", JSONObject.NULL).put("rangeKm", JSONObject.NULL)
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val finish = { if (done.compareAndSet(false, true)) synchronized(answer) { resolve(id, JSONObject(answer.toString())) } }
        main.postDelayed({ finish() }, 3000)
        Thread {
            carMakeAndModel()?.let { (maker, model) -> synchronized(answer) { answer.put("maker", maker).put("model", model) } }
            // The SDK answers in the car's units (verified on the emulator 2026-09-28): battery level and capacity in Wh
            // (77400 / 77400 on the emulator's car), range in metres (500000 = 500 km).
            val battery = vehicle.getEvBattery()
            battery.getEvBatteryLevel({ level ->
                battery.getEvBatteryCapacity({ capacity ->
                    if (level != null && capacity != null && capacity > 0f) synchronized(answer) { answer.put("batterySoc", Math.round(level / capacity * 100f)) }
                    vehicle.getOdometer().getRangeRemainingDistance({ range ->
                        if (range != null) synchronized(answer) { answer.put("rangeKm", Math.round(range / 1000f)) }
                        finish()
                    }, { finish() })
                }, { error -> Log.w(TAG, "capacity", error); finish() })
            }, { error -> Log.w(TAG, "battery", error); finish() })
        }.start()
    }

    /** Everything the Vehicle SDK lets an app read about the trip-relevant state of the car, each value as the car
     *  reports it (null when the car does not say within 3 s or refuses): battery (state of charge %, level and capacity
     *  in Wh), range (m), EV efficiency (current / since charge / cumulative, the car's unit), charge port open, charging
     *  switch, odometer and current trip distance (the car's unit), gear, drive mode. Tire pressures are not here:
     *  getTirePressure needs pleos.car.permission.PRIVATE_CAR_SAFETY (SDK 2.3.2 annotation), not granted to an app. */
    private fun car(id: String) {
        val battery = vehicle.getEvBattery(); val odometer = vehicle.getOdometer()
        val reads: List<Pair<String, (put: (Any?) -> Unit, fail: (Exception) -> Unit) -> Unit>> = listOf(
            "batterySoc" to { put, fail -> battery.getEvBatteryStateOfCharge({ put(it) }, fail) },
            "batteryLevelWh" to { put, fail -> battery.getEvBatteryLevel({ put(it) }, fail) },
            "batteryCapacityWh" to { put, fail -> battery.getEvBatteryCapacity({ put(it) }, fail) },
            "rangeM" to { put, fail -> odometer.getRangeRemainingDistance({ put(it) }, fail) },
            "efficiencyCurrent" to { put, fail -> battery.getCurrentEvEfficiency({ put(it) }, fail) },
            "efficiencySinceCharge" to { put, fail -> battery.getSinceChargeEvEfficiency({ put(it) }, fail) },
            "efficiencyCumulative" to { put, fail -> battery.getCumulativeEvEfficiency({ put(it) }, fail) },
            "chargePortOpen" to { put, fail -> battery.isEvChargePortOpen({ put(it) }, fail) },
            "charging" to { put, fail -> battery.getEvChargeSwitchState({ put(it) }, fail) },
            "odometer" to { put, fail -> odometer.getCurrentDistanceDriven({ put(it) }, fail) },
            "tripDistance" to { put, fail -> odometer.getTripCurrentDrivingDistance({ put(it) }, fail) },
            "gear" to { put, fail -> vehicle.getGear().getCurrentGearSelectionState({ put(it?.name) }, fail) },
            "driveMode" to { put, fail -> vehicle.getDrivingMode().getCurrentDrivingModeState({ put(it?.name) }, fail) },
        )
        val answer = JSONObject(); val waiting = reads.map { it.first }.toMutableSet()
        reads.forEach { (key, _) -> answer.put(key, JSONObject.NULL) }
        var sent = false
        fun finish() = synchronized(answer) { if (!sent) { sent = true; resolve(id, answer) } }
        reads.forEach { (key, start) ->
            var done = false
            val put: (Any?) -> Unit = { value ->
                synchronized(answer) {
                    if (!done) { done = true; answer.put(key, value ?: JSONObject.NULL); waiting.remove(key) }
                    if (waiting.isEmpty()) main.post { finish() }
                }
            }
            val fail: (Exception) -> Unit = { error -> Log.w(TAG, "car $key: ${error.message}"); put(null) }
            runCatching { start(put, fail) }.onFailure { fail(it as? Exception ?: Exception(it)) }
        }
        main.postDelayed({ finish() }, 3000)
    }

    /** The car's position: FusedLocation first (no navigation needed); the built-in navigation's when that fails. */
    private fun location(id: String) {
        val viaNavi = { synchronized(locationWaiters) { locationWaiters.add(id) }; navi.getCurrentLocationInfo() }
        runCatching {
            fused.getLastKnownLocation(object : FusedLocationListener {
                override fun onChanged(location: LocationData?) {
                    if (location == null) { viaNavi(); return }
                    resolve(id, JSONObject().put("latitude", location.latitude).put("longitude", location.longitude).put("source", "fused"))
                }
                override fun onError(error: Exception) { Log.w(TAG, "fused location", error); viaNavi() }
            })
        }.onFailure { Log.w(TAG, "fused location", it); viaNavi() }
    }

    /** Reads a sentence aloud with the car's own voice (TextToSpeech on the device). Answers at once; the voice runs on. */
    private fun speak(id: String, args: String) {
        val text = runCatching { JSONObject(args).optString("text") }.getOrNull().orEmpty().trim()
        if (text.isEmpty()) { reject(id, "no text"); return }
        tts.speak(text.take(500), true)
        resolve(id, JSONObject().put("speaking", true))
    }

    /** Listens once with the car's speech recognition (SpeechToText on the device, ko-KR) and answers the final words;
     *  partial words arrive meanwhile as the page event "pleos-stt". listen-stop ends the listening (the words so far). */
    private fun listen(id: String) {
        listening?.let { reject(it, "replaced") }
        listening = id; heard = ""
        stt.request()
        // Silence never ends on its own on the emulator (no final result): stop after 20 s with whatever was heard
        main.postDelayed({ if (listening == id) { runCatching { stt.stop() }; endListening(heard) } }, 20000)
    }
    private fun endListening(text: String) {
        val id = listening ?: return; listening = null
        resolve(id, JSONObject().put("text", text.trim()))
    }

    /** Watches the trip's stops with FusedLocation proximity alerts ({ stops: [{ id, latitude, longitude }], radius m }):
     *  entering or leaving a stop's circle reaches the page as the event "pleos-arrive" { id, transition: ENTER | EXIT }.
     *  A new call replaces the stops watched before. */
    private fun watchStops(id: String, args: String) {
        unwatchStops()
        val body = JSONObject(args)
        val stops = body.getJSONArray("stops")
        val radius = body.optDouble("radius", 300.0).toFloat()
        for (i in 0 until stops.length()) {
            val stop = stops.getJSONObject(i)
            val stopId = stop.getString("id")
            val request = ProximityAlertRequest(stop.getDouble("latitude"), stop.getDouble("longitude"), radius,
                setOf(ProximityTransition.ENTER, ProximityTransition.EXIT))
            fused.addProximityAlert(request, object : ProximityAlertListener {
                override fun onRegistered(alertId: String) { synchronized(alerts) { alerts[stopId] = alertId } }
                override fun onTransition(event: ProximityAlertEvent) {
                    Log.i(TAG, "stop $stopId ${event.transition}")
                    // Arriving brings Witty in front of the navigation so the stop board is seen (the navigation runs on)
                    if (event.transition == ProximityTransition.ENTER) runCatching {
                        context.startActivity(android.content.Intent(context, MainActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    }.onFailure { Log.w(TAG, "to front", it) }
                    evaluate("window.dispatchEvent(new CustomEvent('pleos-arrive', { detail: { id: ${JSONObject.quote(stopId)}, " +
                        "transition: ${JSONObject.quote(event.transition.name)} } }))")
                }
                override fun onError(error: Exception) { Log.w(TAG, "proximity $stopId", error) }
            })
        }
        resolve(id, JSONObject().put("watching", stops.length()))
    }
    private fun unwatchStops() = synchronized(alerts) {
        alerts.values.forEach { alert -> runCatching { fused.removeProximityAlert(alert) } }
        alerts.clear()
    }

    private fun carMakeAndModel(): Pair<String, String>? = runCatching {
        val car = Car.createCar(context) ?: return null
        try {
            val info = car.getCarManager(Car.INFO_SERVICE) as CarInfoManager
            val maker = info.manufacturer?.trim().orEmpty(); val model = info.model?.trim().orEmpty()
            Log.i(TAG, "car says maker='$maker' model='$model' year=${info.modelYear}")
            // The emulator's car is "Hyundai 42dot" / "Connect": not a model anyone drives.
            if (maker.isEmpty() || model.isEmpty() || model.equals("Connect", ignoreCase = true)) null else maker to model
        } finally { car.disconnect() }
    }.onFailure { Log.w(TAG, "car info", it) }.getOrNull()

    private fun navigate(id: String, args: String) {
        val stops = JSONArray(args).let { array -> (0 until array.length()).map { i -> array.getJSONObject(i) } }
            .map { Stop(it.getString("name"), it.getDouble("latitude"), it.getDouble("longitude")) }
        if (stops.isEmpty()) { reject(id, "no stops"); return }
        val destination = stops.last()
        pendingWaypoints = stops.dropLast(1).take(2)
        navi.requestRoute(RouteInfo(longitude = destination.longitude, latitude = destination.latitude, poiName = destination.name,
            poiId = "", address = "", poiSubId = "", routeOption = RouteOption.entries.first()))
        resolve(id, JSONObject().put("accepted", pendingWaypoints.size + 1))
    }

    // Via points attach once the destination's route stands (verified: addWaypoint before onRouteStarted is dropped).
    override fun onRouteStarted(info: RouteStartInfo) {
        val waypoints = pendingWaypoints; pendingWaypoints = emptyList()
        waypoints.forEachIndexed { index, stop ->
            navi.addWaypoint(RequestWaypointInfo(longitude = stop.longitude, latitude = stop.latitude, poiName = stop.name,
                waypointIndex = if (index == 0) WaypointIndex.FIRST else WaypointIndex.SECOND, poiId = "", address = "", poiSubId = ""))
        }
    }
    override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
        val waiting = synchronized(locationWaiters) { locationWaiters.toList().also { locationWaiters.clear() } }
        val answer = JSONObject().put("latitude", info.latitude).put("longitude", info.longitude).put("source", "navi")
        waiting.forEach { resolve(it, answer) }
    }
    override fun onError(code: NaviErrorCode) { Log.w(TAG, "navi error $code") }

    private fun resolve(id: String, json: JSONObject) = evaluate(
        // logged so the emulator run can be read from logcat
        "".also { Log.i(TAG, "resolve #$id ${json.toString().take(200)}") } +"window.__pleosResolve(${JSONObject.quote(id)}, ${JSONObject.quote(json.toString())})")
    private fun reject(id: String, message: String) = evaluate(
        "".also { Log.w(TAG, "reject #$id $message") } +"window.__pleosReject(${JSONObject.quote(id)}, ${JSONObject.quote(message)})")

    // NaviHelper 3.x gives every listener method a default; the rest is of no interest to the shell.
    override fun onRouteCancelled() { pendingWaypoints = emptyList() }

    companion object { const val TAG = "JourneyManagerPleos" }
}
