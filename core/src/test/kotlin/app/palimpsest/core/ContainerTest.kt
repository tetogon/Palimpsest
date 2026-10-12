package app.palimpsest.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ContainerTest {
    private val file = File.createTempFile("heap", ".bin").also { it.delete() }

    @After
    fun cleanup() {
        file.delete()
    }

    /** A fresh Container object over the same file, as after an app restart. */
    private fun open() = Container.openOrCreate(file, TestSodium.crypto)

    private fun Container.create(password: String) = createVault(password)!!

    private fun VaultSession.titles() = entries().map { it.head.title }

    @Test
    fun newContainerAlreadyHasChaff() {
        assertTrue(open().blockCount() >= 16)
        assertEquals(0L, file.length() % Format.BLOCK_SIZE)
    }

    @Test
    fun wrongPasswordOpensNothing() {
        val c = open()
        c.create("right").close()
        assertNull(c.unlock("wrong"))
    }

    @Test
    fun entriesSurviveReopen() {
        val s = open().create("pw")
        val id = s.save(null, "Day 1", "hello")
        s.save(id, "Day 1", "hello, edited")
        s.close()

        val again = open().unlock("pw")!!
        assertEquals("hello, edited", again.entry(id)!!.head.body)
        assertEquals(1, again.entries().size)
    }

    @Test
    fun passwordCannotBeReused() {
        val c = open()
        c.create("pw").close()
        assertNull(c.createVault("pw"))
    }

    @Test
    fun vaultsDoNotSeeEachOther() {
        val c = open()
        c.create("decoy").apply { save(null, "groceries", "") }.close()
        c.create("hidden").apply { save(null, "secret", "") }.close()

        assertEquals(listOf("groceries"), c.unlock("decoy")!!.titles())
        assertEquals(listOf("secret"), c.unlock("hidden")!!.titles())
    }

    @Test
    fun purgeOverwritesInPlace() {
        val s = open().create("pw")
        val id = s.save(null, "t", "first version")
        val sizeBefore = file.length()
        val bytesBefore = file.readBytes()
        s.save(id, "t", "second version")
        // The old revision was overwritten, not appended over: the prefix changed.
        val after = file.readBytes()
        assertTrue(file.length() > sizeBefore)
        assertTrue(!bytesBefore.contentEquals(after.copyOf(bytesBefore.size)))
        s.delete(id)
        assertNull(s.entry(id))
    }

    /** D = decoy, R = real; R has an edited entry, so it also owns PAD blocks. */
    private fun twoVaults(c: Container) {
        c.create("D").apply { save(null, "groceries", "") }.close()
        c.create("R").apply {
            val id = save(null, "secret", "x".repeat(10_000))
            save(id, "secret", "edited")
        }.close()
    }

    @Test
    fun armedWipeFiresOnceOnNextUnlockAndLeavesNoTrace() {
        val c = open()
        twoVaults(c)
        c.unlock("D")!!.apply {
            assertSame(Container.ArmWipeResult.ARMED, c.armWipe(this, "R"))
            assertTrue(wipeArmed)
        }.close()
        assertEquals(listOf("secret"), c.unlock("R")!!.titles()) // opening R does not fire it

        val sizeBefore = file.length()
        val shown = c.unlock("D")!!
        assertTrue(file.length() >= sizeBefore) // shredded in place, never truncated
        assertEquals(listOf("groceries"), shown.titles())
        assertTrue(!shown.wipeArmed) // rule removed itself
        shown.close()

        assertNull(c.unlock("R"))
        assertEquals(listOf("groceries"), c.unlock("D")!!.titles()) // decoy password keeps working
    }

    @Test
    fun disarmedWipeDoesNothing() {
        val c = open()
        twoVaults(c)
        c.unlock("D")!!.apply {
            c.armWipe(this, "R")
            c.disarmWipe(this)
        }.close()
        c.unlock("D")!!.close()
        assertEquals(listOf("secret"), c.unlock("R")!!.titles())
    }

    @Test
    fun armWipeIsValidated() {
        val c = open()
        twoVaults(c)
        val s = c.unlock("D")!!
        assertSame(Container.ArmWipeResult.NOT_FOUND, c.armWipe(s, "nope"))
        assertSame(Container.ArmWipeResult.SAME_VAULT, c.armWipe(s, "D"))
        assertTrue(!s.wipeArmed)
    }

    @Test
    fun deleteVaultShredsOnlyItself() {
        val c = open()
        twoVaults(c)
        val sizeBefore = file.length()
        c.deleteVault(c.unlock("R")!!)
        assertNull(c.unlock("R"))
        assertTrue(file.length() >= sizeBefore)
        assertEquals(listOf("groceries"), c.unlock("D")!!.titles())
    }

    @Test
    fun changePasswordRetiresTheOldOne() {
        val c = open()
        twoVaults(c)
        val s = c.unlock("R")!!
        assertSame(Container.ChangePasswordResult.WRONG_PASSWORD, c.changePassword(s, "D", "R2"))
        assertSame(Container.ChangePasswordResult.PASSWORD_TAKEN, c.changePassword(s, "R", "D"))
        assertSame(Container.ChangePasswordResult.CHANGED, c.changePassword(s, "R", "R2"))
        // The session still works after the keywrap moved, including delete.
        s.save(null, "after change", "")
        s.close()
        assertNull(c.unlock("R"))
        assertEquals(setOf("secret", "after change"), c.unlock("R2")!!.titles().toSet())
        c.deleteVault(c.unlock("R2")!!)
        assertNull(c.unlock("R2"))
    }

    @Test
    fun tornTailIsRepaired() {
        open().create("pw").close()
        file.appendBytes(ByteArray(100))
        assertNotNull(open().unlock("pw"))
        assertEquals(0L, file.length() % Format.BLOCK_SIZE)
    }
}
