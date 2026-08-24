package com.smartmirror.tablet

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import java.io.IOException

class MainActivity : ComponentActivity() {

    private val httpClient = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ControllerScreen(httpClient)
                }
            }
        }
    }
}

private data class CatalogProduct(val id: Int, val name: String, val category: String, val colorKey: String?, val asset: String?)

private fun sendCommand(
    client: OkHttpClient,
    boxIp: String,
    shirt: Boolean? = null,
    pants: Boolean? = null,
    shirtProductId: Int? = null,
    pantsProductId: Int? = null,
    onStatus: (Boolean) -> Unit = {}
) {
    val base = "http://$boxIp:8080/set".toHttpUrlOrNull() ?: return onStatus(false)
    val url = base.newBuilder().apply {
        shirt?.let { addQueryParameter("shirt", if (it) "1" else "0") }
        pants?.let { addQueryParameter("pants", if (it) "1" else "0") }
        shirtProductId?.let { addQueryParameter("shirtProductId", it.toString()) }
        pantsProductId?.let { addQueryParameter("pantsProductId", it.toString()) }
    }.build()

    client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = onStatus(false)
        override fun onResponse(call: Call, response: Response) {
            response.close()
            onStatus(true)
        }
    })
}

private fun buildProductMultipart(
    name: String,
    category: String,
    colorKey: String?,
    imageBytes: ByteArray?
): MultipartBody.Builder {
    val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("name", name)
        .addFormDataPart("category", category)
    colorKey?.let { builder.addFormDataPart("colorKey", it) }
    imageBytes?.let { builder.addFormDataPart("image", "upload.png", it.toRequestBody("image/png".toMediaType())) }
    return builder
}

private fun addProduct(
    client: OkHttpClient,
    boxIp: String,
    name: String,
    category: String,
    colorKey: String?,
    imageBytes: ByteArray?,
    onDone: (Boolean) -> Unit
) {
    val url = "http://$boxIp:8080/addProduct".toHttpUrlOrNull() ?: return onDone(false)
    val body = buildProductMultipart(name, category, colorKey, imageBytes).build()
    client.newCall(Request.Builder().url(url).post(body).build()).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = onDone(false)
        override fun onResponse(call: Call, response: Response) {
            val ok = response.isSuccessful
            response.close()
            onDone(ok)
        }
    })
}

private fun updateProduct(
    client: OkHttpClient,
    boxIp: String,
    id: Int,
    name: String,
    category: String,
    colorKey: String?,
    imageBytes: ByteArray?,
    onDone: (Boolean) -> Unit
) {
    val url = "http://$boxIp:8080/updateProduct".toHttpUrlOrNull() ?: return onDone(false)
    val body = buildProductMultipart(name, category, colorKey, imageBytes)
        .addFormDataPart("id", id.toString())
        .build()
    client.newCall(Request.Builder().url(url).post(body).build()).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = onDone(false)
        override fun onResponse(call: Call, response: Response) {
            val ok = response.isSuccessful
            response.close()
            onDone(ok)
        }
    })
}

private fun deleteProduct(
    client: OkHttpClient,
    boxIp: String,
    id: Int,
    onDone: (Boolean) -> Unit
) {
    val base = "http://$boxIp:8080/deleteProduct".toHttpUrlOrNull() ?: return onDone(false)
    val url = base.newBuilder().addQueryParameter("id", id.toString()).build()
    val body = "".toRequestBody(null)
    client.newCall(Request.Builder().url(url).post(body).build()).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = onDone(false)
        override fun onResponse(call: Call, response: Response) {
            val ok = response.isSuccessful
            response.close()
            onDone(ok)
        }
    })
}

private fun fetchProducts(
    client: OkHttpClient,
    boxIp: String,
    onResult: (List<CatalogProduct>) -> Unit,
    onStatus: (Boolean) -> Unit = {}
) {
    val url = "http://$boxIp:8080/products".toHttpUrlOrNull() ?: return onStatus(false)
    client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = onStatus(false)
        override fun onResponse(call: Call, response: Response) {
            response.use {
                val body = it.body?.string()
                if (body == null) {
                    onStatus(false)
                    return
                }
                val array = JSONArray(body)
                val products = (0 until array.length()).map { i ->
                    val obj = array.getJSONObject(i)
                    CatalogProduct(
                        id = obj.getInt("id"),
                        name = obj.getString("name"),
                        category = obj.getString("category"),
                        colorKey = obj.optString("colorKey").ifEmpty { null },
                        asset = obj.optString("asset").ifEmpty { null }
                    )
                }
                onResult(products)
                onStatus(true)
            }
        }
    })
}

@Composable
private fun ControllerScreen(httpClient: OkHttpClient) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("tablet_prefs", Context.MODE_PRIVATE) }

    var boxIp by remember { mutableStateOf(prefs.getString("boxIp", "") ?: "") }
    var shirtOn by remember { mutableStateOf(true) }
    var pantsOn by remember { mutableStateOf(true) }
    var products by remember { mutableStateOf<List<CatalogProduct>>(emptyList()) }
    var connected by remember { mutableStateOf<Boolean?>(null) }

    var showAdmin by remember { mutableStateOf(false) }
    var showForm by remember { mutableStateOf(false) }
    var formTarget by remember { mutableStateOf<CatalogProduct?>(null) }
    var formName by remember { mutableStateOf("") }
    var formCategory by remember { mutableStateOf("shirt") }
    var formColorKey by remember { mutableStateOf("") }
    var formImageUri by remember { mutableStateOf<Uri?>(null) }
    var formBusy by remember { mutableStateOf(false) }

    fun refreshProducts() {
        fetchProducts(httpClient, boxIp, onResult = { products = it }, onStatus = { connected = it })
    }

    fun openAddForm() {
        formTarget = null
        formName = ""
        formCategory = "shirt"
        formColorKey = ""
        formImageUri = null
        showForm = true
    }

    fun openEditForm(product: CatalogProduct) {
        formTarget = product
        formName = product.name
        formCategory = product.category
        formColorKey = product.colorKey ?: ""
        formImageUri = null
        showForm = true
    }

    val pickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        formImageUri = uri
    }

    LaunchedEffect(Unit) {
        if (boxIp.isNotBlank()) {
            refreshProducts()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Smart Mirror Controller")

        OutlinedTextField(
            value = boxIp,
            onValueChange = {
                boxIp = it
                prefs.edit().putString("boxIp", it).apply()
            },
            label = { Text("Box IP address") },
            modifier = Modifier.padding(top = 16.dp)
        )

        when (connected) {
            true -> Text("● Connected", color = Color(0xFF2E7D32), modifier = Modifier.padding(top = 4.dp))
            false -> Text("● Box unreachable — check IP and Wi-Fi", color = Color(0xFFC62828), modifier = Modifier.padding(top = 4.dp))
            null -> {}
        }

        Row(modifier = Modifier.padding(top = 24.dp)) {
            Text("Shirt")
            Switch(
                checked = shirtOn,
                onCheckedChange = {
                    shirtOn = it
                    sendCommand(httpClient, boxIp, shirt = it, onStatus = { ok -> connected = ok })
                }
            )
        }

        Row(modifier = Modifier.padding(top = 8.dp)) {
            Text("Pants")
            Switch(
                checked = pantsOn,
                onCheckedChange = {
                    pantsOn = it
                    sendCommand(httpClient, boxIp, pants = it, onStatus = { ok -> connected = ok })
                }
            )
        }

        Row(modifier = Modifier.padding(top = 24.dp)) {
            Button(onClick = { refreshProducts() }) { Text("Load products from Box") }
            OutlinedButton(
                onClick = { showAdmin = !showAdmin; showForm = false },
                modifier = Modifier.padding(start = 12.dp)
            ) { Text(if (showAdmin) "Exit admin" else "Admin: manage products") }
        }

        if (showAdmin && !showForm) {
            Button(onClick = { openAddForm() }, modifier = Modifier.padding(top = 12.dp)) {
                Text("+ Add product")
            }
        }

        if (showForm) {
            Column(modifier = Modifier.padding(top = 16.dp)) {
                Text(if (formTarget == null) "Add product" else "Edit product: ${formTarget?.name}")

                OutlinedTextField(
                    value = formName,
                    onValueChange = { formName = it },
                    label = { Text("Name") },
                    modifier = Modifier.padding(top = 8.dp)
                )

                Button(
                    onClick = { formCategory = if (formCategory == "shirt") "pants" else "shirt" },
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text("Category: $formCategory (tap to change)") }

                OutlinedTextField(
                    value = formColorKey,
                    onValueChange = { formColorKey = it },
                    label = { Text("Color key (optional, shirt only)") },
                    modifier = Modifier.padding(top = 8.dp)
                )

                Row(modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { pickImageLauncher.launch("image/*") }) {
                        Text(if (formImageUri == null) "Pick image" else "Change picked image")
                    }
                    val previewModel: Any? = formImageUri
                        ?: formTarget?.asset?.let { "http://$boxIp:8080/productImage?file=$it" }
                    if (previewModel != null) {
                        AsyncImage(
                            model = previewModel,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(48.dp).padding(start = 12.dp)
                        )
                    }
                }

                Row(modifier = Modifier.padding(top = 16.dp)) {
                    Button(
                        enabled = !formBusy && formName.isNotBlank() && (formTarget != null || formImageUri != null),
                        onClick = {
                            formBusy = true
                            val imageBytes = formImageUri?.let { uri ->
                                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            }
                            val colorKeyOrNull = formColorKey.ifBlank { null }
                            val target = formTarget
                            val onDone: (Boolean) -> Unit = { ok ->
                                formBusy = false
                                connected = ok
                                if (ok) {
                                    showForm = false
                                    refreshProducts()
                                }
                            }
                            if (target == null) {
                                addProduct(httpClient, boxIp, formName, formCategory, colorKeyOrNull, imageBytes, onDone)
                            } else {
                                updateProduct(httpClient, boxIp, target.id, formName, formCategory, colorKeyOrNull, imageBytes, onDone)
                            }
                        }
                    ) { Text(if (formBusy) "Saving..." else "Save") }

                    OutlinedButton(
                        onClick = { showForm = false },
                        modifier = Modifier.padding(start = 12.dp)
                    ) { Text("Cancel") }
                }
            }
        }

        LazyColumn(modifier = Modifier.padding(top = 16.dp)) {
            items(products) { product ->
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Row {
                        if (product.asset != null) {
                            AsyncImage(
                                model = "http://$boxIp:8080/productImage?file=${product.asset}",
                                contentDescription = product.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(56.dp).padding(end = 12.dp)
                            )
                        }
                        Text(product.name, modifier = Modifier.padding(end = 16.dp))
                        Button(onClick = {
                            val ok: (Boolean) -> Unit = { connected = it }
                            when (product.category) {
                                "shirt" -> sendCommand(httpClient, boxIp, shirt = true, shirtProductId = product.id, onStatus = ok)
                                "pants" -> sendCommand(httpClient, boxIp, pants = true, pantsProductId = product.id, onStatus = ok)
                            }
                        }) { Text("Wear") }
                    }
                    if (showAdmin) {
                        Row(modifier = Modifier.padding(top = 4.dp)) {
                            OutlinedButton(onClick = { openEditForm(product) }) { Text("Edit") }
                            OutlinedButton(
                                onClick = {
                                    deleteProduct(httpClient, boxIp, product.id) { ok ->
                                        connected = ok
                                        if (ok) refreshProducts()
                                    }
                                },
                                modifier = Modifier.padding(start = 8.dp)
                            ) { Text("Delete") }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}
