package com.smartmirror.tablet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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

private fun sendCommand(client: OkHttpClient, boxIp: String, shirt: Boolean?, pants: Boolean?) {
    val base = "http://$boxIp:8080/set".toHttpUrlOrNull() ?: return
    val url = base.newBuilder().apply {
        shirt?.let { addQueryParameter("shirt", if (it) "1" else "0") }
        pants?.let { addQueryParameter("pants", if (it) "1" else "0") }
    }.build()

    client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { /* box unreachable, ignore for now */ }
        override fun onResponse(call: Call, response: Response) { response.close() }
    })
}

@Composable
private fun ControllerScreen(httpClient: OkHttpClient) {
    var boxIp by remember { mutableStateOf("") }
    var shirtOn by remember { mutableStateOf(true) }
    var pantsOn by remember { mutableStateOf(true) }

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
                    sendCommand(httpClient, boxIp, shirt = it, pants = null)
                }
            )
        }

        Row(modifier = Modifier.padding(top = 8.dp)) {
            Text("Pants")
            Switch(
                checked = pantsOn,
                onCheckedChange = {
                    pantsOn = it
                    sendCommand(httpClient, boxIp, shirt = null, pants = it)
                }
            )
        }
    }
}
