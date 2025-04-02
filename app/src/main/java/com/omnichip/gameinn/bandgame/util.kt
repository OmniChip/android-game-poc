package com.omnichip.gameinn.bandgame

typealias LogFun = (String)->Unit

fun Long.listOfBits(): List<Int> {
    val seq = mutableListOf<Int>()

    var v = this
    var i = 0
    while (v != 0L) {
        if (v and 1L != 0L)
            seq.add(i)
        v = v shr 1
        ++i
    }

    return seq
}

class LogUtil {
    private var on_debug_log: LogFun? = null

    fun onLog(log: LogFun?) {
        on_debug_log = log
    }

    fun onLog(other: LogUtil) {
        on_debug_log = other.on_debug_log
    }

    fun log(str: String) {
        on_debug_log?.invoke(str)
    }

    fun log(format: String, vararg args: Any?) {
        on_debug_log?.invoke(String.format(format, *args))
    }

    fun fail(e: Exception) {
        e.message?.let { log(it) }
        throw e
    }

    fun fail(format: String, vararg args: Any?) {
        fail(RuntimeException(String.format(format, *args)))
    }

    fun invoke(str: String) {
        log(str)
    }

    fun invoke(format: String, vararg args: Any?) {
        log(format, *args)
    }
}
