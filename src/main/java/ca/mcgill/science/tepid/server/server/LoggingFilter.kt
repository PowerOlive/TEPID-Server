package ca.mcgill.science.tepid.server.server

import ca.mcgill.science.tepid.models.data.PutResponse
import org.apache.log4j.MDC
import org.apache.logging.log4j.kotlin.Logging
import java.util.*
import javax.ws.rs.container.ContainerRequestContext
import javax.ws.rs.container.ContainerRequestFilter
import javax.ws.rs.container.ContainerResponseContext
import javax.ws.rs.container.ContainerResponseFilter
import javax.ws.rs.ext.Provider

/**
 * Note that you may log requests by implementing
 * ContainerRequestFilter
 */
@Provider
class LoggingFilter : ContainerRequestFilter, ContainerResponseFilter {

    override fun filter(requestContext: ContainerRequestContext) {
        MDC.put("req", UUID.randomUUID().toString())
//        log.trace("Request ${requestContext.uriInfo.path}")
    }

    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        val isSuccessful = responseContext.status in 200 until 400
        if (isSuccessful && !Config.DEBUG) return
        val content: String = when (val entity = responseContext.entity) {
            null -> "null"
            is Throwable -> entity.localizedMessage
            is String -> entity
            is Number, is Boolean, is PutResponse -> entity.toString()
            is Collection<*> -> "[${entity::class.java.simpleName} (${entity.size})]"
            is Map<*, *> -> "{${entity::class.simpleName} (${entity.size}}"
            else -> responseContext.entityType.typeName
        }
        val msg = "Response for ${requestContext.uriInfo.path}: ${responseContext.status}: $content"
        if (isSuccessful) logger.trace(msg)
        else logger.error(msg)
    }

    companion object : Logging
}
