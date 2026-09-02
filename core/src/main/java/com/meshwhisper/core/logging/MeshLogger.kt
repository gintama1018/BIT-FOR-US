package com.meshwhisper.core.logging

interface MeshLogger {
    fun d(tag: String, msg: String, tr: Throwable? = null)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String, tr: Throwable? = null)
    fun e(tag: String, msg: String, tr: Throwable? = null)
}

object StdoutLogger : MeshLogger {
    override fun d(tag: String, msg: String, tr: Throwable?) {
        println("[DEBUG] [$tag] $msg")
        tr?.printStackTrace()
    }

    override fun i(tag: String, msg: String) {
        println("[INFO]  [$tag] $msg")
    }

    override fun w(tag: String, msg: String, tr: Throwable?) {
        System.err.println("[WARN]  [$tag] $msg")
        tr?.printStackTrace()
    }

    override fun e(tag: String, msg: String, tr: Throwable?) {
        System.err.println("[ERROR] [$tag] $msg")
        tr?.printStackTrace()
    }
}

object NoOpLogger : MeshLogger {
    override fun d(tag: String, msg: String, tr: Throwable?) {}
    override fun i(tag: String, msg: String) {}
    override fun w(tag: String, msg: String, tr: Throwable?) {}
    override fun e(tag: String, msg: String, tr: Throwable?) {}
}
