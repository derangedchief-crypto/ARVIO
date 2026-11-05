package com.extreme.myhub

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomerIdentityTest {
    @Test fun `verified username replaces panel placeholder and internal reference`() {
        val row = JSONObject().put("name", "Panel line 946").put("external_reference", "panel:946").put("line_username", "sam.taylor")
        assertEquals(CustomerIdentity("sam.taylor", "Customer"), CustomerIdentity.from(row))
    }
    @Test fun `real customer name is retained alongside username`() {
        val row = JSONObject().put("name", "Sam Taylor").put("line_username", "sam.taylor")
        assertEquals(CustomerIdentity("sam.taylor", "Sam Taylor"), CustomerIdentity.from(row))
    }
    @Test fun `manual customers keep their name without a verified username`() {
        val row = JSONObject().put("name", "Sam Taylor").put("line_username", JSONObject.NULL)
        assertEquals(CustomerIdentity("Sam Taylor", "Customer"), CustomerIdentity.from(row))
    }
    @Test fun `missing verified identity is explicit rather than a panel identifier`() {
        val row = JSONObject().put("name", "Panel line 946").put("line_username", JSONObject.NULL)
        assertEquals(CustomerIdentity("Username awaiting app check-in", "Customer"), CustomerIdentity.from(row))
    }
    @Test fun `unassigned group has a readable explanation`() {
        val row = JSONObject().put("name", "Unassigned devices").put("external_reference", "management:unassigned")
        assertEquals(CustomerIdentity("Unassigned devices", "Devices without a customer assignment"), CustomerIdentity.from(row))
    }
}
