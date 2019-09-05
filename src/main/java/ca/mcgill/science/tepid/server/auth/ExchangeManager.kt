package ca.mcgill.science.tepid.server.auth

import ca.mcgill.science.tepid.server.server.Config
import ca.mcgill.science.tepid.utils.WithLogging
import java.util.*
import javax.naming.NamingException
import javax.naming.directory.*

object ExchangeManager : WithLogging(){

    private val ldapConnector = LdapConnector();

    private val auth = Config.RESOURCE_USER to Config.RESOURCE_CREDENTIALS

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
            val success = setExchangeStudentLdap(sam, exchange)
            val dbUser = SessionManager.queryUserDb(sam)
            val ldapUser = Ldap.queryUserLdap(sam, null) ?: return false
            val mergedUser = SessionManager.mergeUsers(ldapUser, dbUser)
            SessionManager.updateDbWithUser(mergedUser)
            return success
        } else return false
    }

    /**
     * Adds the supplied user to the exchange group
     *
     * @return updated status of the user; false if anything goes wrong
     */
    fun setExchangeStudentLdap(sam: String, exchange: Boolean): Boolean {
        val longUser = sam.contains(".")
        val ldapSearchBase = Config.LDAP_SEARCH_BASE
        val searchFilter = "(&(objectClass=user)(" + (if (longUser) "userPrincipalName" else "sAMAccountName") + "=" + sam + (if (longUser) ("@" + Config.ACCOUNT_DOMAIN) else "") + "))"
        val ctx = ldapConnector.bindLdap(auth) ?: return false
        val searchControls = SearchControls()
        searchControls.searchScope = SearchControls.SUBTREE_SCOPE
        var searchResult: SearchResult? = null
        try {
            val results = ctx.search(ldapSearchBase, searchFilter, searchControls)
            searchResult = results.nextElement()
            results.close()
        } catch (e: Exception) {
        }

        if (searchResult == null) return false
        val cal = Calendar.getInstance()
        val userDn = searchResult.nameInNamespace
        val year = cal.get(Calendar.YEAR)
        val season = if (cal.get(Calendar.MONTH) < 8) "W" else "F"
        val groupDn = "CN=" + Config.EXCHANGE_STUDENTS_GROUP_BASE + "$year$season, " + Config.GROUPS_LOCATION
        val mods = arrayOfNulls<ModificationItem>(1)
        val mod = BasicAttribute("member", userDn)
        // todo check if we should ignore modification action if the user is already in/not in the exchange group?
        mods[0] = ModificationItem(if (exchange) DirContext.ADD_ATTRIBUTE else DirContext.REMOVE_ATTRIBUTE, mod)
        return try {
            ctx.modifyAttributes(groupDn, mods)
            log.info("${if (exchange) "Added $sam to" else "Removed $sam from"} exchange students.")
            exchange
        } catch (e: NamingException) {
            if (e.message!!.contains("LDAP: error code 53")) {
                log.warn("Error removing user from Exchange: {\"sam\":\"$sam\", \"cause\":\"not in group\")")
                false
            } else if (e.message!!.contains("LDAP: error code 68")) {
                log.warn("Error adding user from Exchange: {\"sam\":\"$sam\", \"cause\":\"already in group\")")
                true
            } else {
                log.warn("Error adding to exchange students. {\"sam\":\"$sam\", \"userDN\":\"$userDn\",\"groupDN\":\"$groupDn\", \"cause\":null}")
                e.printStackTrace()
                false
            }
        }
    }


}