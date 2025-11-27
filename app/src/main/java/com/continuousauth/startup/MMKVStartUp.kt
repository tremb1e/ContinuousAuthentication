package com.continuousauth.startup

import android.content.Context
import com.continuousauth.utils.SpUtils
import com.rousetime.android_startup.AndroidStartup
import com.tencent.mmkv.MMKV

class MMKVStartUp : AndroidStartup<Boolean>() {
    override fun callCreateOnMainThread(): Boolean = false

    override fun create(context: Context): Boolean? {
        MMKV.initialize(context)
        SpUtils.getInstance()
        return true
    }

    override fun waitOnMainThread(): Boolean = true
}