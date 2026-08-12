package com.shhh

/** Bridge between the running service and the UI, so the readout can show live sensor values. */
object ShhhState {

    data class Info(
        val running: Boolean = false,
        val wakeUpSensor: Boolean = false,
        val sensorName: String = "",
        val eventsPerSec: Float = 0f,
        val sample: FlipDetector.Snapshot? = null,
        val lastChangeText: String = "",
        /** Wall-clock ms of the last engage, or 0 if it has not silenced yet this session. */
        val lastSilencedAtMs: Long = 0L,
        /** How long the last completed silence lasted, in ms. */
        val lastHeldMs: Long = 0L,
    )

    @Volatile
    var info: Info = Info()
        private set

    @Volatile
    private var listener: ((Info) -> Unit)? = null

    fun observe(l: ((Info) -> Unit)?) {
        listener = l
        l?.invoke(info)
    }

    fun update(transform: (Info) -> Info) {
        val next = transform(info)
        info = next
        listener?.invoke(next)
    }
}
