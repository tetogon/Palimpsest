package app.palimpsest.core

/** How many chaff blocks to add (spec §9). Starting parameters, to be tuned by simulation. */
internal object Chaff {
    fun onCreate(crypto: Crypto) = 16 + crypto.randomBelow(49)

    /** [written] is the number of non-chaff blocks in the batch. */
    fun onCommit(crypto: Crypto, written: Int): Int {
        val u = crypto.randomBelow(1_000_000) / 1_000_000.0
        return geometric(crypto) + kotlin.math.ceil(u * written).toInt() + burst(crypto)
    }

    fun onSessionEnd(crypto: Crypto) = geometric(crypto) + burst(crypto)

    /** Daily background job: half of the days add nothing at all. */
    fun background(crypto: Crypto) = if (crypto.randomBelow(2) == 0) 0 else geometric(crypto) + burst(crypto)

    /** Geometric(½) on {0, 1, 2, …}, mean 1. */
    private fun geometric(crypto: Crypto): Int {
        var n = 0
        while (n < 64 && crypto.randomBelow(2) == 0) n++
        return n
    }

    private fun burst(crypto: Crypto) = if (crypto.randomBelow(100) == 0) 16 + crypto.randomBelow(113) else 0
}
