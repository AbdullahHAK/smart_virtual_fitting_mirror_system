package com.smartmirror.tablet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
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

private data class CatalogProduct(val id: Int, val name: String, val category: String, val colorKey: String?)

private fun sendCommand(
    client: OkHttpClient,
    boxIp: String,
    shirt: Boolean? = null,
    pants: Boolean? = null,
    shirtColor: String? = null
) {
    val base = "http://$boxIp:8080/set".toHttpUrlOrNull() ?: return
    val url = base.newBuilder().apply {
        shirt?.let { addQueryParameter("shirt", if (it) "1" else "0") }
        pants?.let { addQueryParameter("pants", if (it) "1" else "0") }
        shirtColor?.let { addQueryParameter("shirtColor", it) }
    }.build()

    client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { /* box unreachable, ignore for now */ }
        override fun onResponse(call: Call, response: Response) { response.close() }
    })
}

private fun fetchProducts(client: OkHttpClient, boxIp: String, onResult: (List<CatalogProduct>) -> Unit) {
    val url = "http://$boxIp:8080/products".toHttpUrlOrNull() ?: return
    client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { /* box unreachable, ignore for now */ }
        override fun onResponse(call: Call, response: Response) {
            response.use {
                val body = it.body?.string() ?: return
                val array = JSONArray(body)
                val products = (0 until array.length()).map { i ->
                    val obj = array.getJSONObject(i)
                    CatalogProduct(
                        id = obj.getInt("id"),
                        name = obj.getString("name"),
                        category = obj.getString("category"),
                        colorKey = obj.optString("colorKey").ifEmpty { null }
                    )
                }
                onResult(products)
            }
        }
    })
}

@Composable
private fun ControllerScreen(httpClient: OkHttpClient) {
    var boxIp by remember { mutableStateOf("") }
    var shirtOn by remember { mutableStateOf(true) }
    var pantsOn by remember { mutableStateOf(true) }
    var products by remember { mutableStateOf<List<CatalogProduct>>(emptyList()) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Smart Mirror Controller")

        OutlinedTextField(
            value = boxIp,
            onValueChange = { boxIp = it },
            label = { Text("Box IP address") },
            modifier = Modifier.padding(top = 16.dp)
        )

        Row(modifier = Modifier.padding(top = 24.dp)) {
            Text("Shirt")
            Switch(
                checked = shirtOn,
                onCheckedChange = {
                    shirtOn = it
                    sendCommand(httpClient, boxIp, shirt = it)
                }
            )
        }

        Row(modifier = Modifier.padding(top = 8.dp)) {
            Text("Pants")
            Switch(
                checked = pantsOn,
                onCheckedChange = {
                    pantsOn = it
                    sendCommand(httpClient, boxIp, pants = it)
                }
            )
        }

        Button(
            onClick = { fetchProducts(httpClient, boxIp) { products = it } },
            modifier = Modifier.padding(top = 24.dp)
        ) { Text("Load products from Box") }

        LazyColumn(modifier = Modifier.padding(top = 16.dp)) {
            items(products) { product ->
                Row(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(product.name, modifier = Modifier.padding(end = 16.dp))
                    Button(onClick = {
                        when (product.category) {
                            "shirt" -> sendCommand(httpClient, boxIp, shirt = true, shirtColor = product.colorKey)
                            "pants" -> sendCommand(httpClient, boxIp, pants = true)
                        }
                    }) { Text("Wear") }
                }
            }
        }
    }
}
