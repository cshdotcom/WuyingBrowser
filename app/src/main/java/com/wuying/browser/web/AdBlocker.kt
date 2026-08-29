package com.wuying.browser.web

import android.net.Uri
import android.util.LruCache
import com.wuying.browser.util.WuyingLog
import java.util.regex.Pattern

/**
 * 广告拦截器
 *
 * 双层策略：
 * 1. 黑名单域名匹配（启动时从内置 hosts.txt + 用户配置的 EasyList 加载）
 * 2. URL 关键字规则匹配（popunder / ads / banner / tracking 等常见模式）
 *
 * 使用内存 LruCache 加速查询，避免每次请求都过一遍正则
 */
object AdBlocker {

    /** 黑名单域名集合 */
    private val blockedDomains = HashSet<String>().apply {
        // 内置常见广告 / 追踪域名
        addAll(
            listOf(
                "doubleclick.net",
                "googlesyndication.com",
                "googleadservices.com",
                "google-analytics.com",
                "googletagmanager.com",
                "googletagservices.com",
                "adservice.google.com",
                "adnxs.com",
                "criteo.com",
                "criteo.net",
                "pubmatic.com",
                "rubiconproject.com",
                "openx.net",
                "taboola.com",
                "outbrain.com",
                "scorecardresearch.com",
                "quantserve.com",
                "adsrvr.org",
                "bidswitch.net",
                "casalemedia.com",
                "moatads.com",
                "adcolony.com",
                "applovin.com",
                "chartboost.com",
                "unityads.unity3d.com",
                "vungle.com",
                "facebook.com/tr",
                "connect.facebook.net",
                "analytics.twitter.com",
                "ads.twitter.com",
                "ads.linkedin.com",
                "ads.tiktok.com",
                "analytics.tiktok.com",
                "business-api.tiktok.com",
                "tencentads.com",
                "qq.com/cgi-bin",
                "baidu.com/cpro",
                "pos.baidu.com",
                "cpro.baidustatic.com",
                "cbjs.baidu.com",
                "umeng.com",
                "umeng.co",
                "umengcloud.com",
                "cnzz.com",
                "umengcache.com",
                "tanx.com",
                "alimama.com",
                "tanx.com",
                "mmstat.com",
                "aliyun.com/process",
                "miui.com/ads",
                "adview.cn",
                "admaster.com.cn",
                "mediav.com",
                "domob.cn",
                "adcdn.com",
                "youmi.net",
                "aduu.cn",
                "adtiming.com",
                "domob.cn",
                "inmobi.com",
                "moad.cn",
                "admob.com"
            )
        )
    }

    /** URL 关键字模式（匹配即拦截） */
    private val patternRules = listOf(
        Pattern.compile("/ads/", Pattern.CASE_INSENSITIVE),
        Pattern.compile("/adservice/", Pattern.CASE_INSENSITIVE),
        Pattern.compile("/adserver/", Pattern.CASE_INSENSITIVE),
        Pattern.compile("/ad/\\w+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("/popunder", Pattern.CASE_INSENSITIVE),
        Pattern.compile("/pop\\.js", Pattern.CASE_INSENSITIVE),
        Pattern.compile("ad\\.banner", Pattern.CASE_INSENSITIVE),
        Pattern.compile("pagead\\d*", Pattern.CASE_INSENSITIVE),
        Pattern.compile("/analytics\\.js", Pattern.CASE_INSENSITIVE),
        Pattern.compile("/ga\\.js", Pattern.CASE_INSENSITIVE),
        Pattern.compile("/gtag/js", Pattern.CASE_INSENSITIVE),
        Pattern.compile("tracker", Pattern.CASE_INSENSITIVE),
        Pattern.compile("/stats", Pattern.CASE_INSENSITIVE)
    )

    /** 缓存最近 200 个 URL 的判定结果 */
    private val resultCache = LruCache<String, Boolean>(200)

    /**
     * 判定一个 URL 是否应被拦截
     */
    @Synchronized
    fun shouldBlock(rawUrl: String): Boolean {
        if (rawUrl.isBlank()) return false
        resultCache.get(rawUrl)?.let { return it }

        var blocked = false
        try {
            val host = Uri.parse(rawUrl).host ?: ""
            // 黑名单域名
            if (host.isNotEmpty()) {
                for (d in blockedDomains) {
                    if (host == d || host.endsWith(".$d")) {
                        blocked = true
                        break
                    }
                }
            }
            // URL 模式
            if (!blocked) {
                for (p in patternRules) {
                    if (p.matcher(rawUrl).find()) {
                        blocked = true
                        break
                    }
                }
            }
        } catch (t: Throwable) {
            WuyingLog.w("AdBlock", "解析失败 $rawUrl", t)
        }

        resultCache.put(rawUrl, blocked)
        return blocked
    }

    /**
     * 添加自定义黑名单域名（运行时 / 设置页用）
     */
    @Synchronized
    fun addDomain(domain: String) {
        blockedDomains.add(domain.trim().lowercase())
        resultCache.evictAll()
    }

    @Synchronized
    fun removeDomain(domain: String) {
        blockedDomains.remove(domain.trim().lowercase())
        resultCache.evictAll()
    }
}
