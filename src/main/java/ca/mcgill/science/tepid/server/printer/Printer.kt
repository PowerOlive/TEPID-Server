package ca.mcgill.science.tepid.server.printer

import ca.mcgill.science.tepid.models.data.FullDestination
import ca.mcgill.science.tepid.models.data.PrintJob
import ca.mcgill.science.tepid.models.enums.PrintError
import ca.mcgill.science.tepid.server.rest.Users
import ca.mcgill.science.tepid.server.util.*
import ca.mcgill.science.tepid.utils.WithLogging
import org.tukaani.xz.XZInputStream
import java.io.*
import java.util.concurrent.*

/**
 * Handler that manages all job requests
 */
object Printer : WithLogging() {

    class PrintException(message: String) : RuntimeException(message) {
        constructor(printError: PrintError) : this(printError.msg)
    }

    private val executor: ExecutorService = ThreadPoolExecutor(5, 30, 10, TimeUnit.MINUTES,
            ArrayBlockingQueue<Runnable>(300, true))
    private val runningTasks: MutableMap<String, Future<*>> = ConcurrentHashMap()
    private val lock: Any = Any()

    /**
     * Run an task in the service
     * Upon completion, it will remove itself from [runningTasks]
     */
    private fun submit(id: String, action: () -> Unit) {
        log.info("Submitting task $id")
        val future = executor.submit {
            try {
                action()
            } finally {
                cancel(id)
            }
        }
        runningTasks[id] = future
    }

    private fun cancel(id: String) {
        try {
            val future = runningTasks.remove(id)
            if (future?.isDone == false)
                future.cancel(true)
        } catch (e: Exception) {
            log.error("Failed to cancel job $id", e)
        }
    }

    private val tmpPath: String by lazy {
        if (System.getProperty("os.name").startsWith("Windows"))
            "${System.getProperty("java.io.tmpdir")}\\tepid"
        else
            "/tmp/tepid"
    }

    private const val INDICATOR_COLOR = "/ProcessColorModel /DeviceCMYK"
    private const val INDICATOR_MONOCHROME = "/ProcessColorModel /DeviceGray"

    /**
     * Returns true if monochrome was detected,
     * or false if color was detected
     * Defaults to monochrome
     */
    private fun BufferedReader.isMonochrome(): Boolean {
        for (line in lines()) {
            if (line.contains(INDICATOR_MONOCHROME))
                return true
            if (line.contains(INDICATOR_COLOR))
                return false
        }
        return true
    }

    /**
     * Attempts to start printing the given job
     * Returns a pair of success, message
     * Note that a successful response merely indicates that the thread was properly created
     * Anything handled by the thread will not be reflected here
     */
    fun print(id: String, stream: InputStream, debug: Boolean = Config.DEBUG): Pair<Boolean, String> {

        log.debug("Receiving job data $id")
        val tmpDir = File(tmpPath)
        if (!tmpDir.exists() && !tmpDir.mkdirs()) {
            log.error("Failed to create tmp path $tmpPath")
            return false to "Failed to create tmp path"
        }

        try {
            // todo test and validate
            //write compressed job to disk
            val tmpXz = File("${tmpDir.absolutePath}/$id.ps.xz")
            tmpXz.copyFrom(stream)
            //let db know we have received data
            CouchDb.updateWithResponse<PrintJob>(id) {
                file = tmpXz.absolutePath
                log.info("Updating job $id with path $file")
                received = System.currentTimeMillis()
            }

            submit(id) {
                // Generates a random file name with our prefix and suffix
                val tmp = File.createTempFile("tepid", ".ps")
                try {
                    //decompress data
                    val decompress = XZInputStream(FileInputStream(tmpXz))
                    tmp.copyFrom(decompress)

                    // Detect PostScript monochrome instruction
                    val br = BufferedReader(FileReader(tmp.absolutePath))
                    val now = System.currentTimeMillis()
                    val psMonochrome = br.isMonochrome()
                    log.trace("Detected ${if (psMonochrome) "monochrome" else "colour"} for job $id in ${System.currentTimeMillis() - now} ms")
                    //count pages
                    val inkCov = Gs.inkCoverage(tmp) ?: throw PrintException("Internal Error")
                    val color = if (psMonochrome) 0
                    else inkCov.filter { !it.monochrome }.size
                    log.trace("Job $id has ${inkCov.size} pages, $color in color")

                    //update page count and status in db
                    var j2: PrintJob = CouchDb.update(id) {
                        pages = inkCov.size
                        colorPages = color
                        processed = System.currentTimeMillis()
                    } ?: throw PrintException("Could not update")

                    //check if user has color printing enabled
                    if (color > 0 && SessionManager.queryUser(j2.userIdentification, null)?.colorPrinting != true)
                        throw PrintException(PrintError.COLOR_DISABLED)

                    //check if user has sufficient quota to print this job
                    if (Users.getQuota(j2.userIdentification) < inkCov.size + color * 2)
                        throw PrintException(PrintError.INSUFFICIENT_QUOTA)

                    //add job to the queue
                    j2 = QueueManager.assignDestination(id)
                    //todo check destination field
                    val destination = j2.destination ?: throw PrintException(PrintError.INVALID_DESTINATION)

                    val dest = CouchDb.path(destination).getJson<FullDestination>()
                    if (sendToSMB(tmp, dest, debug)) {
                        j2.printed = System.currentTimeMillis()
                        CouchDb.path(id).putJson(j2)
                        log.info("${j2._id} sent to destination")
                    } else {
                        throw PrintException("Could not send to destination")
                    }
                } finally {
                    tmp.delete()
                }
            }
            return true to "Successfully created request $id"
        } catch (e: Exception) {
            log.error("Job $id failed", e)
            val msg = (e as? PrintException)?.message ?: "Failed to process"
            failJob(id, msg)
            return false to msg
        }
    }

    internal fun sendToSMB(f: File, destination: FullDestination, debug: Boolean): Boolean {
        if (!f.isFile) {
            log.error("File does not exist at ${f.absolutePath}")
            return false
        }

        log.trace("Sending file ${f.name} ${f.length()} to ${destination.name}")
        if (debug || destination.path?.isBlank() != false) {
            //this is a dummy destination
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
            }
            log.info("Sent dummy ${f.name} to ${destination.name}")
            return true
        }
        try {
            val p = ProcessBuilder("smbclient", "//${destination.path}", destination.password, "-c",
                    "print ${f.absolutePath}", "-U", destination.domain + "\\${destination.username}", "-mSMB3").start()
            p.waitFor()
        } catch (e: IOException) {
            log.error("File ${f.name} failed with ${e.message}")
            return false
        } catch (e: InterruptedException) {
            log.error("File ${f.name} interrupted ${e.message}")
            return false
        }
        log.trace("File ${f.name} sent to ${destination.name}")
        return true
    }

    /**
     * Update job db and cancel executor
     */
    private fun failJob(id: String, error: String) {
        CouchDb.updateWithResponse<PrintJob>(id) {
            fail(error)
        }
        cancel(id)
    }

    fun clearOldJobs() {
        synchronized(lock) {
            try {
                val jobs = CouchDb.getViewRows<PrintJob>("oldJobs") {
                    query("endkey" to System.currentTimeMillis() - 1800000)
                }

                jobs.forEach { j ->
                    j.fail("Timed out")
                    val id = j._id ?: return@forEach
                    CouchDb.path(id).putJson(j)
                    cancel(id)
                }
            } catch (e: Exception) {
                log.error("General failure", e)
            }
        }
    }
}