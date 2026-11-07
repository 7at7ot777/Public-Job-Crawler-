package com.jobcrawler.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopicsTest {
    private val main = "7000000000"

    @Test
    fun `a private topic link is the supergroup id and the topic`() {
        assertEquals(Destination("-1001234567890", 3), Topics.parse("https://t.me/c/1234567890/3", main))
        // A link to one message inside the topic still names the topic.
        assertEquals(Destination("-1001234567890", 3), Topics.parse("t.me/c/1234567890/3/42", main))
    }

    @Test
    fun `the other forms`() {
        assertEquals(Destination("@mygroup", 7), Topics.parse("https://t.me/mygroup/7", main))
        assertEquals(Destination("-1001234567890", 5), Topics.parse("-1001234567890:5", main))
        assertEquals(Destination("-1001234567890", null), Topics.parse("-1001234567890", main))
        assertEquals(Destination(main, 9), Topics.parse(" 9 ", main))
    }

    @Test
    fun `General is the chat itself, and nonsense is nothing`() {
        assertEquals(Destination("-100123", null), Topics.parse("-100123:1", main))
        assertNull(Topics.parse("not a topic", main))
        assertNull(Topics.parse("https://t.me/c/abc/3", main))
        assertNull(Topics.parse("", main))
        assertNull(Topics.parse(null, main))
    }

    @Test
    fun `a blank or unreadable value posts to the channel`() {
        assertEquals(Destination(main, null), Topics.resolve("", main))
        assertEquals(Destination(main, null), Topics.resolve("???", main))
        assertEquals(Destination("-1001234567890", 3), Topics.resolve("https://t.me/c/1234567890/3", main))
    }
}
