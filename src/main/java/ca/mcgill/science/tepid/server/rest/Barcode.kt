package ca.mcgill.science.tepid.server.rest

import ca.mcgill.science.tepid.server.db.barcodesdb
import ca.mcgill.science.tepid.server.db.query
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import javax.ws.rs.GET
import javax.ws.rs.Path
import javax.ws.rs.Produces
import javax.ws.rs.core.MediaType

@Path("/barcode")
class Barcode {
    /**
     * Listen for next barcode event
     *
     * @return JsonNode once event is received
     */
    @GET
    @Path("/_wait")
    @Produces(MediaType.APPLICATION_JSON)
    fun getBarcode(): JsonNode {
        val change = barcodesdb.path("_changes").query(
                "feed" to "longpoll",
                "since" to "now",
                "include_docs" to "true"
        ).request(MediaType.APPLICATION_JSON)
                .get(ObjectNode::class.java)
        return change.get("results").get(0).get("doc")
    }
}
