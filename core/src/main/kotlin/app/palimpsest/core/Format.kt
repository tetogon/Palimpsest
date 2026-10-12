package app.palimpsest.core

/** Constants of container format v1. See docs/format-spec.md §2. */
object Format {
    const val BLOCK_SIZE = 4096
    const val SALT_LEN = 16
    const val R_LEN = 16
    const val LOC_LEN = 8
    const val NONCE_LEN = R_LEN + LOC_LEN
    const val TAG_LEN = 16
    const val PT_LEN = BLOCK_SIZE - NONCE_LEN - TAG_LEN
    const val FRAME_HDR = 27
    const val FRAME_CAP = PT_LEN - FRAME_HDR
    const val KEY_LEN = 32

    const val ARGON_OPS = 3L
    const val ARGON_MEM = 64L * 1024 * 1024

    const val MAX_OP_PARTS = 256
    const val MAX_BLOB_PARTS = 65_536

    val AAD_PREFIX: ByteArray = "plmp1-block".toByteArray(Charsets.US_ASCII)
    val CTX_PW: ByteArray = "plmp1-pw".toByteArray(Charsets.US_ASCII)
    val CTX_VK: ByteArray = "plmp1-vk".toByteArray(Charsets.US_ASCII)

    const val ENTRY_DIARY = 1
    const val ENTRY_NOTE = 2
    const val ENTRY_SETTINGS = 0xFF

    /** Vault settings are an entry with this id (spec §6.2). */
    val SETTINGS_ID = Id(ByteArray(16))

    /** settings TLV: locators of the vault to shred on next unlock (spec §10.3). */
    const val T_WIPE_TARGET = 0x45
}
