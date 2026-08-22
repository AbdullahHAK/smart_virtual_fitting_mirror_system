package com.smartmirror.box

import fi.iki.elonen.NanoHTTPD

// Receives commands from the Tablet Controller over the local Wi-Fi network.
// GET /set?shirt=0|1&pants=0|1&shirtColor=blue|red|green
class CommandServer(
    port: Int,
    private val onSet: (shirt: Boolean?, pants: Boolean?, shirtColor: String?) -> Unit
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        if (session.uri != "/set") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "unknown route")
        }
        val params = session.parms
        val shirt = params["shirt"]?.let { it == "1" }
        val pants = params["pants"]?.let { it == "1" }
        val shirtColor = params["shirtColor"]
        onSet(shirt, pants, shirtColor)
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "ok")
    }
}
