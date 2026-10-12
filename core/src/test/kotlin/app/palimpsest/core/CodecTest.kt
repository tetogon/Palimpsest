package app.palimpsest.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CodecTest {
    private val crypto = TestSodium.crypto

    @Test
    fun frameRoundTripAcrossParts() {
        val data = crypto.randomBytes(Format.FRAME_CAP * 2 + 10)
        val frames = Frame.split(Kind.OP, Id.random(crypto), data)
        assertEquals(3, frames.size)
        val asm = Assembler()
        frames.reversed().forEachIndexed { i, f -> asm.add(Frame.decode(f.encode())!!, i) }
        assertArrayEquals(data, asm.complete().single().data)
    }

    @Test
    fun emptyObjectIsOnePart() {
        val frames = Frame.split(Kind.OP, Id.random(crypto), ByteArray(0))
        assertEquals(1, frames.size)
        assertNotNull(Frame.decode(frames[0].encode()))
    }

    @Test
    fun frameRejectsNonZeroPadding() {
        val pt = Frame(Kind.OP, Id.random(crypto), 0, 1, byteArrayOf(1, 2, 3)).encode()
        pt[Format.PT_LEN - 1] = 1
        assertNull(Frame.decode(pt))
    }

    @Test
    fun frameRejectsShortNonLastPart() {
        assertNull(Frame.decode(Frame(Kind.OP, Id.random(crypto), 0, 2, byteArrayOf(1)).encode()))
    }

    @Test
    fun frameRejectsBadCounts() {
        assertNull(Frame.decode(Frame(Kind.OP, Id.random(crypto), 1, 1, ByteArray(0)).encode()))
        assertNull(Frame.decode(Frame(Kind.KEYWRAP, Id.random(crypto), 0, 2, ByteArray(Format.FRAME_CAP)).encode()))
    }

    @Test
    fun incompleteObjectIsNotReturned() {
        val frames = Frame.split(Kind.OP, Id.random(crypto), ByteArray(Format.FRAME_CAP + 1))
        val asm = Assembler()
        asm.add(frames[0], 0)
        assertEquals(0, asm.complete().size)
    }

    @Test
    fun randomPlaintextNeverCrashesParsers() {
        repeat(5_000) {
            val pt = crypto.randomBytes(Format.PT_LEN)
            if (it % 2 == 0) pt.fill(0, 27 + (it % 100), Format.PT_LEN)
            Frame.decode(pt)
            OpCodec.decode(Id.random(crypto), crypto.randomBytes(it % 300))
        }
    }

    @Test
    fun tlvRejectsOverlongAndTruncated() {
        assertNull(Tlv.parse(byteArrayOf(1, 0x80.toByte(), 0x00), 0))
        assertNull(Tlv.parse(byteArrayOf(1, 5, 1, 2), 0))
        assertEquals(1, Tlv.parse(byteArrayOf(1, 2, 7, 7), 0)!!.size)
    }

    @Test
    fun opRoundTripPreservesUnknownFields() {
        val op = PutOp(
            Id.random(crypto), 42, Id.random(crypto), Id.random(crypto),
            setOf(Id.random(crypto), Id.random(crypto)), Format.ENTRY_DIARY, 1000, 19_000,
            "Заголовок", "Тело ✓", listOf("a", "b"), listOf(TlvField(0x30, byteArrayOf(9))),
        )
        val back = OpCodec.decode(op.opId, OpCodec.encode(op)) as PutOp
        assertEquals(op.title, back.title)
        assertEquals(op.body, back.body)
        assertEquals(op.supersedes, back.supersedes)
        assertEquals(op.tags, back.tags)
        assertEquals(0x30, back.unknown.single().tag)
    }

    @Test
    fun opRejectsSelfSupersede() {
        val id = Id.random(crypto)
        val op = DeleteOp(id, 1, Id.random(crypto), Id.random(crypto), setOf(id))
        assertNull(OpCodec.decode(id, OpCodec.encode(op)))
    }
}
