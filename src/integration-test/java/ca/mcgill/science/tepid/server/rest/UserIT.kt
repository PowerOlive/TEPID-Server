package ca.mcgill.science.tepid.server.rest

import ca.mcgill.science.tepid.server.ITBase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import kotlin.test.assertTrue

class UserIT : ITBase() {

    val endpoints: Users by lazy {
        Users()
    }

    @Test
    fun testAutoSuggest() {
        val u = server.testApi.queryUsers(server.testUser, 10).execute().body() ?: fail("derp")

        assertTrue { u.size > 0 }
        assertTrue { u.any { it.shortUser == server.testUser } }
    }
}