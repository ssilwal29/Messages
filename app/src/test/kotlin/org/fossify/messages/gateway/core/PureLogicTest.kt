package org.fossify.messages.gateway.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventIdsTest {
    @Test
    fun `ids depend only on provider id and date`() {
        assertEquals("sms:812:1729600000000", EventIds.sms(812, 1_729_600_000_000))
        assertEquals("mms:812:1729600000000", EventIds.mms(812, 1_729_600_000L * 1000))
        // The receiver and the catch-up scanner build the id from the same row: same inputs, same id.
        assertEquals(EventIds.mms(5, 42_000), EventIds.mms(5, 42_000))
    }

    @Test
    fun `sms and mms sequences never collide`() {
        assertTrue(EventIds.sms(1, 1000) != EventIds.mms(1, 1000))
    }

    @Test
    fun `attachment keys extend the event id`() {
        assertEquals("mms:812:1729600000000:part:3", EventIds.attachmentKey(EventIds.mms(812, 1_729_600_000_000), 3))
    }

    @Test
    fun `ids stay within 200 characters`() {
        assertTrue(EventIds.attachmentKey(EventIds.mms(Long.MAX_VALUE, Long.MAX_VALUE), Long.MAX_VALUE).length <= 200)
    }
}

class HandleNormalizerTest {
    @Test
    fun `national format becomes E164 with the SIM country`() {
        assertEquals("+15551234567", US.normalize("(555) 123-4567"))
        assertEquals("+15551234567", US.normalize("555-123-4567"))
        assertEquals("+15551234567", US.normalize("1 555 123 4567"))
        assertEquals("+442079460958", HandleNormalizer("gb").normalize("020 7946 0958"))
    }

    @Test
    fun `E164 input is unchanged`() {
        assertEquals("+15551234567", US.normalize("+15551234567"))
        assertEquals("+15551234567", US.normalize(" +1 (555) 123-4567 "))
    }

    @Test
    fun `00 international prefix`() {
        // Where 00 is the international prefix, libphonenumber reads it directly.
        assertEquals("+15551234567", HandleNormalizer("DE").normalize("0015551234567"))
        assertEquals("+442079460958", HandleNormalizer("FR").normalize("00 44 20 7946 0958"))
        // From a US SIM (international prefix 011), a 00 number is still read as international.
        assertEquals("+442079460958", US.normalize("0044 20 7946 0958"))
    }

    @Test
    fun `short codes are left as they are`() {
        assertEquals("72975", US.normalize("72975"))
        assertEquals("611", US.normalize("611"))
        assertEquals("262966", US.normalize("262966"))
        assertEquals("VERIZON", US.normalize("VERIZON"))
    }

    @Test
    fun `email handles are lower case`() {
        assertEquals("sarah@icloud.com", US.normalize(" Sarah@iCloud.com "))
    }

    @Test
    fun `one person always produces one string`() {
        val forms = listOf("+15551234567", "5551234567", "(555) 123-4567", "1-555-123-4567", "+1 555 123 4567")
        assertEquals(setOf("+15551234567"), forms.map(US::normalize).toSet())
    }

    @Test
    fun `unknown region falls back to keeping digits`() {
        val noRegion = HandleNormalizer(null)
        assertEquals("+15551234567", noRegion.normalize("+1 555 123 4567"))
        assertEquals("5551234567", noRegion.normalize("555 123 4567"))
    }
}

class MmsAddressesTest {
    private val own = OwnNumbers(listOf("+1 555 000 0000"), US)

    @Test
    fun `sender and participants come from addr rows`() {
        val rows = listOf(
            MmsAddresses.AddrRow("+15551234567", MmsAddresses.TYPE_FROM),
            MmsAddresses.AddrRow("5550000000", MmsAddresses.TYPE_TO),
            MmsAddresses.AddrRow("(555) 765-4321", MmsAddresses.TYPE_TO),
            MmsAddresses.AddrRow("+15559998888", MmsAddresses.TYPE_CC),
            MmsAddresses.AddrRow("+15551112222", MmsAddresses.TYPE_BCC),
        )
        val parties = MmsAddresses.parse(rows, US, own)
        assertEquals("+15551234567", parties.from)
        assertEquals(listOf("+15551234567", "+15557654321", "+15559998888"), parties.participants)
    }

    @Test
    fun `placeholder and duplicate addresses are dropped`() {
        val rows = listOf(
            MmsAddresses.AddrRow("insert-address-token", MmsAddresses.TYPE_FROM),
            MmsAddresses.AddrRow("+15551234567", MmsAddresses.TYPE_FROM),
            MmsAddresses.AddrRow("555-123-4567", MmsAddresses.TYPE_TO),
            MmsAddresses.AddrRow("+15550000000", MmsAddresses.TYPE_TO),
        )
        val parties = MmsAddresses.parse(rows, US, own)
        assertEquals("+15551234567", parties.from)
        assertEquals(listOf("+15551234567"), parties.participants)
    }

    @Test
    fun `no FROM row means no sender`() {
        val parties = MmsAddresses.parse(listOf(MmsAddresses.AddrRow("+15551234567", MmsAddresses.TYPE_TO)), US, own)
        assertNull(parties.from)
    }
}

class RecipientCheckTest {
    private val own = OwnNumbers(listOf("+15550000000"), US)
    private val group = listOf("+15551234567", "(555) 765-4321")

    private fun check(type: String, recipients: List<String>, thread: List<String>?) =
        RecipientCheck.check(type, recipients, thread, US, own)

    @Test
    fun `equal sets pass`() {
        assertEquals(RecipientCheck.Result.Ok, check(TYPE_MMS, listOf("+15557654321", "+15551234567"), group))
        assertEquals(RecipientCheck.Result.Ok, check(TYPE_SMS, listOf("+15551234567"), listOf("5551234567")))
    }

    @Test
    fun `own number stored in the thread is ignored`() {
        val thread = group + "+1 555 000 0000"
        assertEquals(RecipientCheck.Result.Ok, check(TYPE_MMS, listOf("+15551234567", "+15557654321"), thread))
    }

    @Test
    fun `extra person in the thread fails`() {
        val result = check(TYPE_MMS, listOf("+15551234567", "+15557654321"), group + "+15559998888")
        assertTrue(result is RecipientCheck.Result.Mismatch)
        assertTrue((result as RecipientCheck.Result.Mismatch).reason.contains("also in thread: +15559998888"))
    }

    @Test
    fun `missing person fails`() {
        val result = check(TYPE_MMS, listOf("+15551234567", "+15557654321", "+15559998888"), group)
        assertTrue((result as RecipientCheck.Result.Mismatch).reason.contains("not in thread: +15559998888"))
    }

    @Test
    fun `own number in recipients fails`() {
        val result = check(TYPE_MMS, listOf("+15551234567", "+15550000000"), group)
        assertTrue((result as RecipientCheck.Result.Mismatch).reason.contains("own number"))
    }

    @Test
    fun `wrong type for the count fails`() {
        assertTrue(check(TYPE_SMS, listOf("+15551234567", "+15557654321"), group) is RecipientCheck.Result.Mismatch)
        assertTrue(check(TYPE_MMS, listOf("+15551234567"), listOf("+15551234567")) is RecipientCheck.Result.Mismatch)
        assertTrue(check("rcs", listOf("+15551234567"), listOf("+15551234567")) is RecipientCheck.Result.Mismatch)
    }

    @Test
    fun `missing thread fails`() {
        val result = check(TYPE_SMS, listOf("+15551234567"), null)
        assertEquals(RecipientCheck.Result.Mismatch("thread does not exist"), result)
    }

    @Test
    fun `duplicate recipients fail`() {
        assertTrue(check(TYPE_MMS, listOf("+15551234567", "+15551234567"), group) is RecipientCheck.Result.Mismatch)
    }
}

class BackoffTest {
    @Test
    fun `stays between one and sixty seconds`() {
        val backoff = Backoff.doorbell()
        repeat(200) { attempt ->
            val delay = backoff.delayFor(attempt % 12)
            assertTrue(delay in 1_000..60_000)
        }
        assertTrue(backoff.delayFor(20) >= 30_000)
    }
}

class DrainCoordinatorTest {
    @Test
    fun `a request during a drain makes it run once more`() {
        val coordinator = DrainCoordinator()
        var runs = 0
        val ranHere = coordinator.request {
            runs++
            if (runs == 1) {
                // Another `ready` arrives mid-drain: it doesn't run a second drain in parallel.
                assertEquals(false, coordinator.request { error("must not run concurrently") })
            }
        }
        assertTrue(ranHere)
        assertEquals(2, runs)
    }
}
