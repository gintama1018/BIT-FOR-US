package com.meshwhisper.app.logging

import android.util.Log
import com.meshwhisper.core.logging.MeshLogger

/**
 * Android implementation of MeshLogger wrapping android.util.Log.
 */
class AndroidLogger : MeshLogger {
    override fun d(tag: String, msg: String, tr: Throwable?) {
        if (tr != null) Log.d(tag, msg, tr) else Log.d(tag, msg)
    }

    override fun i(tag: String, msg: String) {
        Log.i(tag, msg)
    }

    override fun w(tag: String, msg: String, tr: Throwable?) {
        if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
    }

    override fun e(tag: String, msg: String, tr: Throwable?) {
        if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
    }
}
