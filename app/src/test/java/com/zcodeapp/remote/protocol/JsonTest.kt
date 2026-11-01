package com.zcodeapp.remote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonTest {
    @Test
    fun parsesNestedAndEscapes() {
        val v = JsonValue.parse("""{"a":[1,2,{"b":"中\"文"}],"c":null,"d":true,"e":-1.5e3}""")
        val obj = v as JsonValue.Obj
        assertEquals("[a, c, d, e]", obj.fields.keys.toString())
        assertEquals(3, obj.arr("a")!!.items.size)
        assertEquals("中\"文", (obj.arr("a")!!.items[2] as JsonValue.Obj).str("b"))
        assertTrue(obj.fields["c"] is JsonValue.Null)
        assertTrue(obj.fields["d"] is JsonValue.Bool)
        assertEquals(-1500.0, (obj.fields["e"] as JsonValue.Num).double, 0.0001)
    }

    @Test
    fun unicodeEscape() {
        val v = JsonValue.parse("\"\\u4e2d\\u6587\"")
        assertEquals("中文", (v as JsonValue.Str).value)
    }

    @Test
    fun rejectsMalformed() {
        for (bad in listOf("{", "[1,", "\"unclosed", "{\"a\":}", "trailing {}", "{'a':1}")) {
            try {
                JsonValue.parse(bad)
                fail("should reject: $bad")
            } catch (e: JsonParseException) {
                // expected
            }
        }
    }

    @Test
    fun numbers() {
        val v = JsonValue.parse("""{"l":21474836480,"i":42,"neg":-7}""") as JsonValue.Obj
        assertEquals(21474836480L, v.num("l"))
        assertEquals(42L, v.num("i"))
        assertEquals(-7L, v.num("neg"))
        assertNull(v.num("missing"))
    }
}
