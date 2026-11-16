package cc.futurvo.ideadock.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON codec is the only thing between this plugin and every QuickDock/DSH
 * message, so it gets the boring exhaustive treatment: escapes, numbers, nesting,
 * and the malformed input that a probe has to turn into a failure - not a crash.
 */
class JsonTest {

    @Test
    fun `parses objects, arrays and scalars`() {
        val value = Json.parse("""{"a":1,"b":[true,false,null],"c":{"d":"e"},"f":1.5}""") as Map<*, *>
        assertEquals(1L, value["a"])
        assertEquals(listOf(true, false, null), value["b"])
        assertEquals("e", (value["c"] as Map<*, *>)["d"])
        assertEquals(1.5, value["f"])
    }

    @Test
    fun `keeps the insertion order of a map`() {
        val parsed = Json.parseObject("""{"z":1,"a":2,"m":3}""")!!
        assertEquals(listOf("z", "a", "m"), parsed.keys.toList())
    }

    @Test
    fun `round-trips a document`() {
        val original = linkedMapOf<String, Any?>(
            "name" to "dsh-quickdock",
            "apiVersion" to 2L,
            "capabilities" to listOf("app-proxy", "dock-contexts"),
            "mcpBridge" to linkedMapOf<String, Any?>("enabled" to true, "origin" to null),
            "escaped" to "a\"b\\c\nd\te"
        )
        val text = Json.stringify(original)
        val parsed = Json.parseObject(text)!!
        assertEquals(original["name"], parsed["name"])
        assertEquals(original["capabilities"], parsed["capabilities"])
        assertEquals("a\"b\\c\nd\te", parsed["escaped"])
    }

    @Test
    fun `escapes control characters and quotes`() {
        assertEquals("\"a\\nb\"", Json.stringLiteral("a\nb"))
        assertEquals("\"a\\\"b\"", Json.stringLiteral("a\"b"))
        assertEquals("\"\\u0001\"", Json.stringLiteral("\u0001"))
        assertEquals("\"héllo\"", Json.stringLiteral("héllo"))
    }

    @Test
    fun `reads typed accessors defensively`() {
        val map = Json.parseObject("""{"s":"x","n":3,"b":true,"list":["a","b"],"obj":{"k":1},"null":null}""")!!
        assertEquals("x", Json.str(map, "s"))
        assertEquals(3L, Json.num(map, "n"))
        assertEquals(true, Json.bool(map, "b"))
        assertEquals(listOf("a", "b"), Json.stringList(map, "list"))
        assertEquals(1L, Json.num(Json.obj(map, "obj")!!, "k"))
        assertNull(Json.str(map, "null"))
        assertNull(Json.str(map, "missing"))
    }

    @Test
    fun `rejects malformed documents`() {
        assertNull(Json.parseObject("{"))
        assertNull(Json.parseObject("not json"))
        assertNull(Json.parseObject("[1,2]"))
        assertNull(Json.parseArray("{}"))
    }

    @Test
    fun `parses numbers as long when they are integral`() {
        val map = Json.parseObject("""{"port":47910,"ratio":0.5,"negative":-3,"exponent":1e3}""")!!
        assertEquals(47910L, map["port"])
        assertEquals(0.5, map["ratio"])
        assertEquals(-3L, map["negative"])
        assertEquals(1000.0, map["exponent"])
    }

    @Test
    fun `handles unicode escapes`() {
        assertEquals("钻", (Json.parse("\"\\u94bb\"") as String))
    }

    @Test
    fun `stringify writes nested arrays and empty containers`() {
        assertEquals("{\"a\":[]}", Json.stringify(linkedMapOf<String, Any?>("a" to emptyList<Any?>())))
        assertEquals("{}", Json.stringify(emptyMap<String, Any?>()))
        assertTrue(Json.stringify(listOf(1, 2)).startsWith("["))
    }
}
