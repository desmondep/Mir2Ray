package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.V2rayConfigManager
import com.v2ray.ang.util.Utils
import java.net.URI

object VlessFmt : FmtBase() {

    private fun sanitizeVlessUri(raw: String): String {
        val input = Utils.fixIllegalUrl(raw.trim())
        val hashIndex = input.indexOf('#')
        val withoutFragment = if (hashIndex >= 0) input.substring(0, hashIndex) else input
        val fragment = if (hashIndex >= 0) input.substring(hashIndex + 1) else ""

        val queryIndex = withoutFragment.indexOf('?')
        if (queryIndex < 0) return input

        val prefix = withoutFragment.substring(0, queryIndex)
        val query = withoutFragment.substring(queryIndex + 1)
        val sanitizedQuery = query.split("&")
            .mapNotNull { part ->
                if (part.isBlank()) return@mapNotNull null
                val equalIndex = part.indexOf('=')
                if (equalIndex <= 0) return@mapNotNull null
                val key = part.substring(0, equalIndex)
                val value = part.substring(equalIndex + 1)

                val safeValue = if (key.equals("headers", true) || key.equals("header", true)) {
                    Utils.urlEncode(value)
                } else {
                    value
                        .replace("#", "%23")
                        .replace("{", "%7B")
                        .replace("}", "%7D")
                        .replace("'", "%27")
                }
                "$key=$safeValue"
            }
            .joinToString("&")

        val safeFragment = if (fragment.isNotBlank()) "#${Utils.urlEncode(Utils.urlDecode(fragment))}" else ""
        return "$prefix?$sanitizedQuery$safeFragment"
    }

    /**
     * Parses a Vless URI string into a ProfileItem object.
     *
     * @param str the Vless URI string to parse
     * @return the parsed ProfileItem object, or null if parsing fails
     */
    fun parse(str: String): ProfileItem? {
        var allowInsecure = MmkvManager.decodeSettingsBool(AppConfig.PREF_ALLOW_INSECURE, false)
        val config = ProfileItem.create(EConfigType.VLESS)

        val uri = try {
            URI(Utils.fixIllegalUrl(str))
        } catch (_: Exception) {
            URI(sanitizeVlessUri(str))
        }
        if (uri.rawQuery.isNullOrEmpty()) return null
        val queryParam = getQueryParam(uri)

        config.remarks = Utils.urlDecode(uri.fragment.orEmpty()).let { if (it.isEmpty()) "none" else it }
        config.server = uri.idnHost
        config.serverPort = uri.port.toString()
        config.password = uri.userInfo
        config.method = queryParam["encryption"] ?: "none"

        getItemFormQuery(config, queryParam, allowInsecure)

        return config
    }

    /**
     * Converts a ProfileItem object to a URI string.
     *
     * @param config the ProfileItem object to convert
     * @return the converted URI string
     */
    fun toUri(config: ProfileItem): String {
        val dicQuery = getQueryDic(config)
        dicQuery["encryption"] = config.method ?: "none"

        return toUri(config, config.password, dicQuery)
    }

    /**
     * Converts a ProfileItem object to an OutboundBean object.
     *
     * @param profileItem the ProfileItem object to convert
     * @return the converted OutboundBean object, or null if conversion fails
     */
    fun toOutbound(profileItem: ProfileItem): OutboundBean? {
        val outboundBean = V2rayConfigManager.createInitOutbound(EConfigType.VLESS)

        outboundBean?.settings?.vnext?.first()?.let { vnext ->
            vnext.address = getServerAddress(profileItem)
            vnext.port = profileItem.serverPort.orEmpty().toInt()
            vnext.users[0].id = profileItem.password.orEmpty()
            vnext.users[0].encryption = profileItem.method
            vnext.users[0].flow = profileItem.flow
        }

        val sni = outboundBean?.streamSettings?.let {
            V2rayConfigManager.populateTransportSettings(it, profileItem)
        }

        outboundBean?.streamSettings?.let {
            V2rayConfigManager.populateTlsSettings(it, profileItem, sni)
        }

        return outboundBean
    }
}