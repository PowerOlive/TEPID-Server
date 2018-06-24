package ca.mcgill.science.tepid.server.util

import ca.mcgill.science.tepid.utils.WithLogging
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.module.kotlin.convertValue
import javax.ws.rs.client.WebTarget
import javax.ws.rs.core.Response

object CouchDb : WithLogging() {

    const val MAIN_VIEW = "_design/main/_view"

    val target
        get() = couchdbOld

    /**
     * We have defined paths for views, this enum lists them out.
     * Now the IDE can check for valid methods
     */
    enum class CouchDbView(viewName :String){
        ByLongUser("byLongUser"),
        ByStudentId("byStudentId"),
        Queues("queues");

        val path : String = "$MAIN_VIEW/$viewName"

    }

    /**
     * Create an [ArrayNode] from the given [data] at field [fieldName]
     */
    fun <T> putArray(fieldName: String, data: Collection<T>): ArrayNode {
        _log.trace("putting array at $fieldName: ${data.joinToString(" | ")}")
        return JsonNodeFactory.instance.objectNode()
                .putArray(fieldName)
                .addAll(mapper.convertValue<ArrayNode>(data))
    }

    fun path(vararg segment: String): WebTarget {
        var target = couchdbOld
        segment.forEach { target = target.path(it) }
        return target
    }

    fun path(couchDbView: CouchDbView): WebTarget {
        return couchdbOld.path(couchDbView.path)
    }

    /*
     * -------------------------------------------
     * View data retriever
     *
     * Given path, retrieve ViewResult variant
     * and return just the row values
     * -------------------------------------------
     */

    inline fun <reified T : Any> getViewRows(path: String): List<T> =
            getViewRows(MAIN_VIEW, path)

    inline fun <reified T : Any> getViewRows(path: String,
                                             targetConfig: WebTarget.() -> WebTarget): List<T> =
            getViewRows(MAIN_VIEW, path, targetConfig)

    inline fun <reified T : Any> getViewRows(base: String, path: String): List<T> =
            getViewRows(base, path, { this })

    inline fun <reified T : Any> getViewRows(base: String, path: String,
                                             targetConfig: WebTarget.() -> WebTarget): List<T> =
            path(base, path).targetConfig().getViewRows()

    /**
     * Helper for getting data at path [id], editing, then putting it back at the same path
     * If the put request is successful, the updated data will be returned
     * If anything went wrong, null will be returned
     */
    inline fun <reified T : Any> update(id: String, action: T.() -> Unit): T? {
        if (id.isBlank()) {
            log.error("Requested update for blank path for ${T::class.java.simpleName}")
            return null
        }
        try {
            val target = path(id)
            val data = target.getJson<T>()
            data.action()
            log.trace("Updating data at $id")
            val response = target.putJson(data)
            if (response.isSuccessful) {
                return data
            }
        } catch (e: Exception) {
        }
        return null
    }

    /**
     * Attempts to update the given target, and returns the response
     */
    inline fun <reified T : Any> updateWithResponse(id: String, action: T.() -> Unit): Response {
        if (id.isBlank()) {
            log.error("Requested update for blank path for ${T::class.java.simpleName}")
            return Response.Status.BAD_REQUEST.text("Empty path")
        }
        return try {
            val target = path(id)
            val data = target.getJson<T>()
            data.action()
            log.trace("Updating data at $id")
            target.putJson(data)
        } catch (e: Exception) {
            log.error("Update with response failed for ${T::class.java.simpleName}", e)
            Response.Status.BAD_REQUEST.text("${e::class.java.simpleName} occurred")
        }
    }

}
