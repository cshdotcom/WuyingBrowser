package com.wuying.browser.util

import android.util.Log

/**
 * 统一日志封装
 * - release 包可关闭 verbose
 * - 自动加 tag 前缀 [Wuying]
 */
object WuyingLog {
    private const val PREFIX = "Wuying"

    fun v(tag: String, msg: String) = Log.v("$PREFIX/$tag", msg)
    fun d(tag: String, msg: String) = Log.d("$PREFIX/$tag", msg)
    fun i(tag: String, msg: String) = Log.i("$PREFIX/$tag", msg)
    fun w(tag: String, msg: String, t: Throwable? = null) =
        if (t != null) Log.w("$PREFIX/$tag", msg, t) else Log.w("$PREFIX/$tag", msg)
    fun e(tag: String, msg: String, t: Throwable? = null) =
        if (t != null) Log.e("$PREFIX/$tag", msg, t) else Log.e("$PREFIX/$tag", msg)
}
