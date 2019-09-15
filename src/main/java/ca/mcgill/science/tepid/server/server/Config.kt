package ca.mcgill.science.tepid.server.server

import ca.mcgill.science.tepid.models.data.About
import ca.mcgill.science.tepid.models.data.AdGroup
import ca.mcgill.science.tepid.server.db.DB
import ca.mcgill.science.tepid.server.db.DbLayer
import ca.mcgill.science.tepid.server.db.HibernateDbLayer
import ca.mcgill.science.tepid.server.printing.GSException
import ca.mcgill.science.tepid.server.printing.Gs
import ca.mcgill.science.tepid.server.util.Utils
import ca.mcgill.science.tepid.utils.*
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LoggerContext
import java.util.*
import javax.persistence.EntityManagerFactory

object Config : WithLogging() {


    private val illegalLDAPCharacters = "[,+\"\\\\<>;=]".toRegex()

    /**
     * Global definition for whether a the build is in debug mode or not
     */
    val DEBUG: Boolean

    /*
     * Server
     */
    val TEPID_URL_PRODUCTION: String
    val TEPID_URL_TESTING: String

    /*
     * DB data
     */
    val DB_URL: String
    val DB_USERNAME: String
    val DB_PASSWORD: String
    var emf : EntityManagerFactory? = null
    /*
     * Barcode data
     */
    val BARCODES_USERNAME: String
    val BARCODES_PASSWORD: String
    val BARCODES_URL: String
    /*
     * TEM data
     */
    val TEM_URL: String

    /*
     * LDAP and Permission Groups
     */

    val LDAP_ENABLED = true

    val LDAP_SEARCH_BASE : String
    val ACCOUNT_DOMAIN : String
    val PROVIDER_URL : String
    val SECURITY_PRINCIPAL_PREFIX : String

    val RESOURCE_USER : String
    val RESOURCE_CREDENTIALS: String

    val EXCHANGE_STUDENTS_GROUP_BASE : String
    val GROUPS_LOCATION : String
    val ELDERS_GROUP : List<AdGroup>
    val CTFERS_GROUP : List<AdGroup>
    val CURRENT_EXCHANGE_GROUP : AdGroup
    val USERS_GROUP : List<AdGroup>

    /*
     * Printing configuration
     */

    val MAX_PAGES_PER_JOB: Int

    /*
     * About information
     */

    val HASH: String
    val TAG: String
    val CREATION_TIMESTAMP: Long
    val CREATION_TIME: String

    /**
     * Encapsulates config data that can be made public
     */
    val PUBLIC: About

    init {
        //TODO: revise to use getNonNull, possibly implement a get with default

        log.info("**********************************")
        log.info("*       Setting up Configs       *")
        log.info("**********************************")

        DefaultProps.withName = {fileName -> listOf(
                FilePropLoader("/etc/tepid/$fileName"),
                FilePropLoader("webapps/tepid/$fileName"),
                JarPropLoader("/$fileName"),
                FilePropLoader("/config/$fileName")
        )}

        DEBUG = PropsURL.TESTING?.toBoolean() ?: true

        TEPID_URL_PRODUCTION = PropsURL.SERVER_URL_PRODUCTION ?: throw RuntimeException()
        TEPID_URL_TESTING = PropsURL.WEB_URL_TESTING ?: TEPID_URL_PRODUCTION

        DB_URL = PropsDB.URL
        DB_USERNAME = PropsDB.USERNAME
        DB_PASSWORD = PropsDB.PASSWORD

        BARCODES_URL = PropsBarcode.BARCODES_URL ?: ""
        BARCODES_USERNAME = PropsBarcode.BARCODES_DB_USERNAME ?: ""
        BARCODES_PASSWORD = PropsBarcode.BARCODES_DB_PASSWORD ?: ""

        LDAP_SEARCH_BASE = PropsLDAP.LDAP_SEARCH_BASE ?: ""
        ACCOUNT_DOMAIN = PropsLDAP.ACCOUNT_DOMAIN ?: ""
        PROVIDER_URL = PropsLDAP.PROVIDER_URL ?: ""
        SECURITY_PRINCIPAL_PREFIX = PropsLDAP.SECURITY_PRINCIPAL_PREFIX ?: ""

        RESOURCE_USER = PropsLDAPResource.LDAP_RESOURCE_USER ?: ""
        RESOURCE_CREDENTIALS = PropsLDAPResource.LDAP_RESOURCE_CREDENTIALS ?: ""

        EXCHANGE_STUDENTS_GROUP_BASE = PropsLDAPGroups.EXCHANGE_STUDENTS_GROUP_BASE ?: ""
        GROUPS_LOCATION = PropsLDAPGroups.GROUPS_LOCATION ?: ""
        ELDERS_GROUP = PropsLDAPGroups.ELDERS_GROUPS?.split(illegalLDAPCharacters)?.map { AdGroup(it) } ?: emptyList()
        CTFERS_GROUP = PropsLDAPGroups.CTFERS_GROUPS?.split(illegalLDAPCharacters)?.map { AdGroup(it) } ?: emptyList()
        
        CURRENT_EXCHANGE_GROUP = {
            val cal = Calendar.getInstance()
            val groupName = EXCHANGE_STUDENTS_GROUP_BASE + cal.get(Calendar.YEAR) + if (cal.get(Calendar.MONTH) < 8) "W" else "F"
            AdGroup(groupName)
        }()
        USERS_GROUP = (PropsLDAPGroups.USERS_GROUPS?.split(illegalLDAPCharacters))?.map { AdGroup(it) }
                ?.plus(CURRENT_EXCHANGE_GROUP)
                ?: emptyList()

        TEM_URL = PropsTEM.TEM_URL ?: ""

        HASH = PropsCreationInfo.HASH ?: ""
        TAG = PropsCreationInfo.TAG ?: ""
        CREATION_TIMESTAMP = PropsCreationInfo.CREATION_TIMESTAMP?.toLongOrNull() ?: -1
        CREATION_TIME = PropsCreationInfo.CREATION_TIME ?: ""

        MAX_PAGES_PER_JOB = PropsPrinting.MAX_PAGES_PER_JOB ?: -1

        if (DEBUG)
            setLoggingLevel(Level.TRACE)
            log.trace(ELDERS_GROUP)
            log.trace(CTFERS_GROUP)
            log.trace(USERS_GROUP)

        /*
         * For logging
         */
        val warnings = mutableListOf<String>()

        log.trace("Validating configs settings")

        log.info("Debug mode: $DEBUG")
        if (DB_URL.isEmpty())
            log.fatal("DB_URL not set")
        if (DB_PASSWORD.isEmpty())
            log.fatal("DB_PASSWORD not set")
        if (RESOURCE_CREDENTIALS.isEmpty())
            log.error("RESOURCE_CREDENTIALS not set")

        log.info("Build hash: $HASH")

        PUBLIC = About(debug = DEBUG,
                ldapEnabled = LDAP_ENABLED,
                startTimestamp = System.currentTimeMillis(),
                startTime = Utils.now(),
                hash = HASH,
                warnings = warnings,
                tag = TAG,
                creationTime = CREATION_TIME,
                creationTimestamp = CREATION_TIMESTAMP)


        log.trace("Completed setting configs")

        log.trace("Initialising subsystems")

        DB = getDb()

        try {
            Gs.testRequiredDevicesInstalled()
        } catch (e: GSException){
            log.fatal("GS ink_cov device unavailable")
        }

        log.trace("Completed initialising subsystems")

    }

    fun setLoggingLevel(level: Level) {
        log.info("Updating log level to $level")
        val ctx = LogManager.getContext(false) as LoggerContext
        val config = ctx.configuration
        val loggerConfig = config.getLoggerConfig(LogManager.ROOT_LOGGER_NAME)
        loggerConfig.level = level
        ctx.updateLoggers()
    }

    fun getDb(): DbLayer {
        val emf = HibernateDbLayer.makeEntityManagerFactory("tepid-pu")
        return HibernateDbLayer(emf)
    }
}