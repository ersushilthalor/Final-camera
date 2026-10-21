package com.hinnka.mycamera.utils

import android.content.Context
import android.util.Log

object BuglyHelper {
    private const val TAG = "BuglyHelper"

    fun init(context: Context) {
        Log.d(TAG, "Initialized BuglyHelper logger")
    }

    fun setUserScene(context: Context, scene: Int) {
        Log.d(TAG, "setUserScene: $scene")
    }

    fun putUserData(context: Context, key: String, value: String) {
        Log.d(TAG, "putUserData: $key=$value")
    }

    fun log(tag: String, msg: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, msg, throwable)
        } else {
            Log.e(tag, msg)
        }
    }

    fun error(throwable: Throwable) {
        Log.e(TAG, "Caught exception", throwable)
    }
}
