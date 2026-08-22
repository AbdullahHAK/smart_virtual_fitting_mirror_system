package com.smartmirror.box

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

// Receives commands from the Tablet Controller over the local Wi-Fi network.
// GET /set?shirt=0|1&pants=0|1&shirtColor=blue|red|green
// GET /products  -> JSON array of the local catalog
class CommandServer(
    port: Int,
    private val getProducts: () -> List<Product>,
    private val onSet: (shirt: Boolean?, pants: Boolean?, shirtColor: String?) -> Unit
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response = when (session.uri) {
        "/set" -> handleSet(session)
        "/products" -> handleProducts()
        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "unknown route")
    }

    private fun handleSet(session: IHTTPSession): Response {
        val params = session.parms
        val shirt = params["shirt"]?.let { it == "1" }
        val pants = params["pants"]?.let { it == "1" }
        val shirtColor = params["shirtColor"]
        onSet(shirt, pants, shirtColor)
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "ok")
    }

    private fun handleProducts(): Response {
        val array = JSONArray()
        for (p in getProducts()) {
            array.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("category", p.category)
                    put("colorKey", p.colorKey)
                }
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", array.toString())
    }
}
