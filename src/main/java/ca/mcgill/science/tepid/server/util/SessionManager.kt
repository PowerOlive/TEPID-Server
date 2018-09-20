package ca.mcgill.science.tepid.server.util

import `in`.waffl.q.Promise
import `in`.waffl.q.Q
import ca.mcgill.science.tepid.models.bindings.LOCAL
import ca.mcgill.science.tepid.models.bindings.withDbData
import ca.mcgill.science.tepid.models.data.FullSession
import ca.mcgill.science.tepid.models.data.FullUser
import ca.mcgill.science.tepid.models.data.User
import ca.mcgill.science.tepid.server.db.*
import ca.mcgill.science.tepid.utils.WithLogging
import com.fasterxml.jackson.databind.node.ObjectNode
import org.mindrot.jbcrypt.BCrypt
import java.math.BigInteger
import java.security.SecureRandom

/**
 * SessionManager is responsible for managing sessions and dealing with the underlying authentication.
 * It is analogous to PAM, in that everything which needs authentication or user querying goes through this.
 * For managing sessions, it can start, resume, and end sessions
 * For user querying, it first checks the DB cache. The cache is updated every time a query to the underlying authentication is made.
 * Since it also provides an interface with the underlying authentication, it also provides username autosuggestion and can set users as exchange.
 */

object SessionManager : WithLogging() {

    private const val HOUR_IN_MILLIS = 60 * 60 * 1000
    private val numRegex = Regex("[0-9]+")
    private val shortUserRegex = Regex("[a-zA-Z]+[0-9]*")

    private val random = SecureRandom()

    fun start(user: FullUser, expiration: Int): FullSession {
        val session = FullSession(user = user, expiration = System.currentTimeMillis() + expiration * HOUR_IN_MILLIS)
        val id = BigInteger(130, random).toString(32)
        session._id = id
        log.trace("Creating session {\"id\":\"$id\", \"shortUser\":\"${user.shortUser}\"}")
        val out = CouchDb.path(id).putJson(session)
        println(out)
        return session
    }

    operator fun get(token: String): FullSession? {
        val session = CouchDb.path(token).getJsonOrNull<FullSession>() ?: return null
        if (session.isValid()) return session
        log.trace("Deleting session token {\"token\":\"$token\", \"expiration\":\"${session.expiration}\", \"now\":\"${System.currentTimeMillis()}\"}")
        CouchDb.path(token).deleteRev()
        return null
    }

    /**
     * Check if session exists and isn't expired
     *
     * @param token sessionId
     * @return true for valid, false otherwise
     */
    fun valid(token: String): Boolean = this[token] != null

    fun end(token: String) {
        //todo test
        CouchDb.path(token).deleteRev()
    }

    /**
     * Authenticates user as appropriate:
     * first with local auth (if applicable), then against LDAP (if enabled)
     *
     * @param sam short user
     * @param pw  password
     * @return authenticated user, or null if auth failure
     */
    fun authenticate(sam: String, pw: String): FullUser? {
        val dbUser = queryUserDb(sam)
        log.trace("Db data found for $sam")
        if (dbUser?.authType == LOCAL) {
            return if (BCrypt.checkpw(pw, dbUser.password)) dbUser else null
        } else if (Config.LDAP_ENABLED) {
            var ldapUser = Ldap.authenticate(sam, pw)
            if (ldapUser != null) {
                ldapUser = mergeUsers(ldapUser, dbUser)
                updateDbWithUser(ldapUser)
            }
            return ldapUser
        } else {
            return null
        }
    }

    /**
     * Retrieve user from DB if available, otherwise retrieves from LDAP
     *
     * @param sam short user
     * @param pw  password
     * @return user if found
     */
    fun queryUser(sam: String?, pw: String?): FullUser? {
        if (sam == null) return null
        log.trace("Querying user: {\"sam\":\"$sam\"}")

        val dbUser = queryUserDb(sam)

        if (dbUser != null) return dbUser

        if (Config.LDAP_ENABLED) {
            if (!sam.matches(shortUserRegex)) return null // cannot query without short user
            val ldapUser = Ldap.queryUserLdap(sam, pw) ?: return null

            updateDbWithUser(ldapUser)

            log.trace("Found user from ldap {\"sam\":\"$sam\", \"longUser\":\"${ldapUser.longUser}\"}")
            return ldapUser
        }
        //finally
        return null
    }

    /**
     * Merge users from LDAP and DB for their corresponding authorities
     * Returns a new users (does not mutate either input
     */
    internal fun mergeUsers(ldapUser: FullUser, dbUser: FullUser?): FullUser {
        // ensure that short users actually match before attempting any merge
        val ldapShortUser = ldapUser.shortUser
                ?: throw RuntimeException("LDAP user does not have a short user. Maybe this will help {\"ldapUser\":\"$ldapUser,\"dbUser\":\"$dbUser\"}")
        if (dbUser == null) return ldapUser
        if (ldapShortUser != dbUser.shortUser) throw RuntimeException("Attempt to merge to different users {\"ldapUser\":\"$ldapUser,\"dbUser\":\"$dbUser\"}")
        // proceed with data merge
        val newUser = ldapUser.copy()
        newUser.withDbData(dbUser)
        newUser.studentId = if (ldapUser.studentId != -1) ldapUser.studentId else dbUser.studentId
        newUser.preferredName = dbUser.preferredName
        newUser.nick = dbUser.nick
        newUser.colorPrinting = dbUser.colorPrinting
        newUser.jobExpiration = dbUser.jobExpiration
        newUser.updateUserNameInformation()
        return newUser
    }

    /**
     * Uploads a [user] to the DB,
     * with logging for failures
     */
    internal fun updateDbWithUser(user: FullUser) {
        log.trace("Update db instance {\"user\":\"${user.shortUser}\"}\n")
        try {
            val response = CouchDb.path("u${user.shortUser}").putJson(user)
            if (response.isSuccessful) {
                val responseObj = response.readEntity(ObjectNode::class.java)
                val newRev = responseObj.get("_rev")?.asText()
                if (newRev != null && newRev.length > 3) {
                    user._rev = newRev
                    log.trace("New rev {\"user\": \"${user.shortUser}\", \"rev\":\"$newRev\"}")
                }
            } else {
                log.error("Updating DB with user failed: {\"user\": \"${user.shortUser}\",\"response\":\"$response\"}")
            }
        } catch (e1: Exception) {
            log.error("Error updating DB with user: {\"user\": \"${user.shortUser}\"}", e1)
        }
    }


    /**
     * Retrieve a [FullUser] directly from the database when supplied with either a
     * short user, long user, or student id
     */
    fun queryUserDb(sam: String?): FullUser? {
        sam ?: return null
        val dbUser = when {
            sam.contains(".") ->
                CouchDb
                        .path(CouchDb.CouchDbView.ByLongUser)
                        .queryParam("key", "\"${sam.substringBefore("@")}%40${Config.ACCOUNT_DOMAIN}\"")
                        .getViewRows<FullUser>()
                        .firstOrNull()
            sam.matches(numRegex) ->
                CouchDb
                        .path(CouchDb.CouchDbView.ByStudentId)
                        .queryParam("key", sam)
                        .getViewRows<FullUser>()
                        .firstOrNull()
            else -> CouchDb.path("u$sam").getJsonOrNull()
        }
        dbUser?._id ?: return null
        log.trace("Found db user {\"sam\":\"$sam\",\"db_id\":\"${dbUser._id}\", \"dislayName\":\"${dbUser.displayName}\"}")
        return dbUser
    }

    /**
     * Sends list of matching [User]s based on current query
     *
     * @param like  prefix
     * @param limit max list size
     * @return list of matching users
     */
    fun autoSuggest(like: String, limit: Int): Promise<List<FullUser>> {
        if (!Config.LDAP_ENABLED) {
            val emptyPromise = Q.defer<List<FullUser>>()
            emptyPromise.resolve(emptyList())
            return emptyPromise.promise
        }
        return Ldap.autoSuggest(like, limit)
    }

    /**
     * Sets exchange student status.
     * Also updates user information from LDAP.
	 * This refreshes the groups and courses of a user,
	 * which allows for thier role to change
     *
     * @param sam      shortUser
     * @param exchange boolean for exchange status
     * @return updated status of the user; false if anything goes wrong
     */
    fun setExchangeStudent(sam: String, exchange: Boolean): Boolean {
        if (Config.LDAP_ENABLED) {
            log.info("Setting exchange status {\"sam\":\"$sam\", \"exchange_status\":\"$exchange\"}")
            val success = Ldap.setExchangeStudent(sam, exchange)
            val dbUser = queryUserDb(sam)
            val ldapUser = Ldap.queryUserLdap(sam, null) ?: return false
            val mergedUser = mergeUsers(ldapUser, dbUser)
            updateDbWithUser(mergedUser)
            return success
        } else return false
    }

}
