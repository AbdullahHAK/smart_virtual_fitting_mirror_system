package com.smartmirror.box

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

// Receives commands from the Tablet Controller over the local Wi-Fi network.
// GET  /set?shirt=0|1&pants=0|1&shirtProductId=<id>&pantsProductId=<id>
// GET  /products  -> JSON array of the local catalog
// GET  /productImage?file=<asset filename>  -> raw PNG bytes
// POST /addProduct     (multipart: name, category, colorKey?, image?) -> {"id": <new id>}
// POST /updateProduct  (multipart: id, name, category, colorKey?, image?)
// POST /deleteProduct?id=<id>
class CommandServer(
    port: Int,
    private val getProducts: () -> List<Product>,
    private val getAssetBytes: (fileName: String) -> ByteArray?,
    private val onAddProduct: (name: String, category: String, colorKey: String?, imageTempPath: String?) -> Long,
    private val onUpdateProduct: (id: Int, name: String, category: String, colorKey: String?, imageTempPath: String?) -> Boolean,
    private val onDeleteProduct: (id: Int) -> Boolean,
    private val onSet: (shirt: Boolean?, pants: Boolean?, shirtProductId: Int?, pantsProductId: Int?) -> Unit
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response = when (session.uri) {
        "/set" -> handleSet(session)
        "/products" -> handleProducts()
        "/productImage" -> handleProductImage(session)
        "/addProduct" -> handleAddProduct(session)
        "/updateProduct" -> handleUpdateProduct(session)
        "/deleteProduct" -> handleDeleteProduct(session)
        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "unknown route")
    }

    private fun handleSet(session: IHTTPSession): Response {
        val params = session.parms
        val shirt = params["shirt"]?.let { it == "1" }
        val pants = params["pants"]?.let { it == "1" }
        val shirtProductId = params["shirtProductId"]?.toIntOrNull()
        val pantsProductId = params["pantsProductId"]?.toIntOrNull()
        onSet(shirt, pants, shirtProductId, pantsProductId)
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "ok")
    }

    private fun handleAddProduct(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "bad request body: ${e.message}")
        }
        val params = session.parms
        val name = params["name"]
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "missing name")
        val category = params["category"]
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "missing category")
        val colorKey = params["colorKey"]?.ifEmpty { null }
        val imageTempPath = files["image"]
        val newId = onAddProduct(name, category, colorKey, imageTempPath)
        if (newId < 0) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "an image is required to add a product")
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", JSONObject().put("id", newId).toString())
    }

    private fun handleUpdateProduct(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "bad request body: ${e.message}")
        }
        val params = session.parms
        val id = params["id"]?.toIntOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "missing/invalid id")
        val name = params["name"]
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "missing name")
        val category = params["category"]
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "missing category")
        val colorKey = params["colorKey"]?.ifEmpty { null }
        val imageTempPath = files["image"]
        val ok = onUpdateProduct(id, name, category, colorKey, imageTempPath)
        return if (ok) newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "ok")
        else newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "product not found")
    }

    private fun handleDeleteProduct(session: IHTTPSession): Response {
        val id = session.parms["id"]?.toIntOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "missing/invalid id")
        val ok = onDeleteProduct(id)
        return if (ok) newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "ok")
        else newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "product not found")
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
                    put("asset", p.asset)
                }
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", array.toString())
    }

    private fun handleProductImage(session: IHTTPSession): Response {
        val fileName = session.parms["file"]
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "missing file param")
        val bytes = getAssetBytes(fileName)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
        return newFixedLengthResponse(Response.Status.OK, "image/png", bytes.inputStream(), bytes.size.toLong())
    }
}
