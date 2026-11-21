package com.kachat.app.repository

/**
 * KaPost Settings > Tip In: what a tap on Tip sends (iOS `KaPostsTipAsset`, 148a5331, with iOS's
 * nil as [ASK]). Ask Each Time and kUSDT turn the instant tip off.
 */
enum class KaPostsTipAsset(val raw: String?) {
    ASK(null),
    KASPA("kaspa"),
    KUSDT("kusdt");

    companion object {
        /** A stored value; anything unknown (or nothing) is [ASK], as iOS's lossy decode. */
        fun fromRaw(raw: String?): KaPostsTipAsset = values().firstOrNull { it.raw != null && it.raw == raw } ?: ASK

        /** What a tap on Tip sends: the Tip In choice, or Kaspa when an instant tip amount is set
         *  (those are always Kaspa); [ASK] = ask each time (iOS `AppSettings.kaPostsTipAssetInUse`). */
        fun inUse(tipAsset: KaPostsTipAsset, defaultTipSompi: Long?, defaultTipFiat: Pair<Double, String>?): KaPostsTipAsset {
            if (tipAsset != ASK) return tipAsset
            if ((defaultTipSompi ?: 0L) > 0L || (defaultTipFiat?.first ?: 0.0) > 0.0) return KASPA
            return ASK
        }
    }
}
